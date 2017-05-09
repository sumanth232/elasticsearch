/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.rounding;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.unit.TimeValue;
import org.joda.time.DateTimeField;
import org.joda.time.DateTimeZone;
import org.joda.time.IllegalInstantException;

import java.io.IOException;
import java.util.Objects;

/**
 * A strategy for rounding long values.
 */
public abstract class Rounding implements Streamable {

    public abstract byte id();

    /**
     * Rounds the given value.
     */
    public abstract long round(long value);

    /**
     * Given the rounded value (which was potentially generated by {@link #round(long)}, returns the next rounding value. For example, with
     * interval based rounding, if the interval is 3, {@code nextRoundValue(6) = 9 }.
     *
     * @param value The current rounding value
     * @return      The next rounding value;
     */
    public abstract long nextRoundingValue(long value);

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    public static Builder builder(DateTimeUnit unit) {
        return new Builder(unit);
    }

    public static Builder builder(TimeValue interval) {
        return new Builder(interval);
    }

    public static class Builder {

        private final DateTimeUnit unit;
        private final long interval;
        private boolean reverseTz;

        private DateTimeZone timeZone = DateTimeZone.UTC;

        public Builder(DateTimeUnit unit) {
            this.unit = unit;
            this.interval = -1;
        }

        public Builder(TimeValue interval) {
            this.unit = null;
            if (interval.millis() < 1)
                throw new IllegalArgumentException("Zero or negative time interval not supported");
            this.interval = interval.millis();
        }

        public Builder timeZone(DateTimeZone timeZone) {
            if (timeZone == null) {
                throw new IllegalArgumentException("Setting null as timezone is not supported");
            }
            this.timeZone = timeZone;
            return this;
        }

        public Builder reverseTz(boolean reverseTz){
            this.reverseTz = reverseTz;
            return this;
        }

        public Rounding build() {
            Rounding timeZoneRounding;
            if (unit != null) {
                timeZoneRounding = new TimeUnitRounding(unit, timeZone, reverseTz);
            } else {
                timeZoneRounding = new TimeIntervalRounding(interval, timeZone);
            }
            return timeZoneRounding;
        }
    }

    static class TimeUnitRounding extends Rounding {

        static final byte ID = 1;

        private DateTimeUnit unit;
        private DateTimeField field;
        private DateTimeZone timeZone;
        private boolean reverseTz;

        TimeUnitRounding() { // for serialization
        }

        TimeUnitRounding(DateTimeUnit unit, DateTimeZone timeZone, boolean reverseTz) {
            this.unit = unit;
            this.field = unit.field(timeZone);
            this.timeZone = timeZone;
            this.reverseTz = reverseTz;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long round(long utcMillis) {
            long rounded = field.roundFloor(utcMillis);
            if (reverseTz == false && timeZone.isFixed() == false) {
                // special cases for non-fixed time zones with dst transitions
                if (timeZone.getOffset(utcMillis) != timeZone.getOffset(rounded)) {
                    /*
                     * the offset change indicates a dst transition. In some
                     * edge cases this will result in a value that is not a
                     * rounded value before the transition. We round again to
                     * make sure we really return a rounded value. This will
                     * have no effect in cases where we already had a valid
                     * rounded value
                     */
                    rounded = field.roundFloor(rounded);
                } else {
                    /*
                     * check if the current time instant is at a start of a DST
                     * overlap by comparing the offset of the instant and the
                     * previous millisecond. We want to detect negative offset
                     * changes that result in an overlap
                     */
                    if (timeZone.getOffset(rounded) < timeZone.getOffset(rounded - 1)) {
                        /*
                         * we are rounding a date just after a DST overlap. if
                         * the overlap is smaller than the time unit we are
                         * rounding to, we want to add the overlapping part to
                         * the following rounding interval
                         */
                        long previousRounded = field.roundFloor(rounded - 1);
                        if (rounded - previousRounded < field.getDurationField().getUnitMillis()) {
                            rounded = previousRounded;
                        }
                    }
                }
            }
            assert rounded == field.roundFloor(rounded);

            if (reverseTz) {
                rounded -= timeZone.getOffset(rounded);
            }
            return rounded;
        }

        @Override
        public long nextRoundingValue(long utcMillis) {
            long floor = round(utcMillis);
            // add one unit and round to get to next rounded value
            long next = round(field.add(floor, 1));
            if (next == floor) {
                // in rare case we need to add more than one unit
                next = round(field.add(floor, 2));
            }
            return next;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            unit = DateTimeUnit.resolve(in.readByte());
            timeZone = DateTimeZone.forID(in.readString());
            field = unit.field(timeZone);
            reverseTz = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeByte(unit.id());
            out.writeString(timeZone.getID());
            out.writeBoolean(reverseTz);
        }

        @Override
        public int hashCode() {
            return Objects.hash(unit, timeZone);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TimeUnitRounding other = (TimeUnitRounding) obj;
            return Objects.equals(unit, other.unit) && Objects.equals(timeZone, other.timeZone);
        }

        @Override
        public String toString() {
            return "[" + timeZone + "][" + unit + "]";
        }
    }

    static class TimeIntervalRounding extends Rounding {

        static final byte ID = 2;

        private long interval;
        private DateTimeZone timeZone;

        TimeIntervalRounding() { // for serialization
        }

        TimeIntervalRounding(long interval, DateTimeZone timeZone) {
            if (interval < 1)
                throw new IllegalArgumentException("Zero or negative time interval not supported");
            this.interval = interval;
            this.timeZone = timeZone;
        }

        @Override
        public byte id() {
            return ID;
        }

        @Override
        public long round(long utcMillis) {
            long timeLocal = timeZone.convertUTCToLocal(utcMillis);
            long rounded = roundKey(timeLocal, interval) * interval;
            long roundedUTC;
            if (isInDSTGap(rounded) == false) {
                roundedUTC = timeZone.convertLocalToUTC(rounded, true, utcMillis);
                // check if we crossed DST transition, in this case we want the
                // last rounded value before the transition
                long transition = timeZone.previousTransition(utcMillis);
                if (transition != utcMillis && transition > roundedUTC) {
                    roundedUTC = round(transition - 1);
                }
            } else {
                /*
                 * Edge case where the rounded local time is illegal and landed
                 * in a DST gap. In this case, we choose 1ms tick after the
                 * transition date. We don't want the transition date itself
                 * because those dates, when rounded themselves, fall into the
                 * previous interval. This would violate the invariant that the
                 * rounding operation should be idempotent.
                 */
                roundedUTC = timeZone.previousTransition(utcMillis) + 1;
            }
            return roundedUTC;
        }

        private static long roundKey(long value, long interval) {
            if (value < 0) {
                return (value - interval + 1) / interval;
            } else {
                return value / interval;
            }
        }

        /**
         * Determine whether the local instant is a valid instant in the given
         * time zone. The logic for this is taken from
         * {@link DateTimeZone#convertLocalToUTC(long, boolean)} for the
         * `strict` mode case, but instead of throwing an
         * {@link IllegalInstantException}, which is costly, we want to return a
         * flag indicating that the value is illegal in that time zone.
         */
        private boolean isInDSTGap(long instantLocal) {
            if (timeZone.isFixed()) {
                return false;
            }
            // get the offset at instantLocal (first estimate)
            int offsetLocal = timeZone.getOffset(instantLocal);
            // adjust instantLocal using the estimate and recalc the offset
            int offset = timeZone.getOffset(instantLocal - offsetLocal);
            // if the offsets differ, we must be near a DST boundary
            if (offsetLocal != offset) {
                // determine if we are in the DST gap
                long nextLocal = timeZone.nextTransition(instantLocal - offsetLocal);
                if (nextLocal == (instantLocal - offsetLocal)) {
                    nextLocal = Long.MAX_VALUE;
                }
                long nextAdjusted = timeZone.nextTransition(instantLocal - offset);
                if (nextAdjusted == (instantLocal - offset)) {
                    nextAdjusted = Long.MAX_VALUE;
                }
                if (nextLocal != nextAdjusted) {
                    // we are in the DST gap
                    return true;
                }
            }
            return false;
        }

        @Override
        public long nextRoundingValue(long time) {
            long timeLocal = time;
            timeLocal = timeZone.convertUTCToLocal(time);
            long next = timeLocal + interval;
            return timeZone.convertLocalToUTC(next, false);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            interval = in.readVLong();
            timeZone = DateTimeZone.forID(in.readString());
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(interval);
            out.writeString(timeZone.getID());
        }

        @Override
        public int hashCode() {
            return Objects.hash(interval, timeZone);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TimeIntervalRounding other = (TimeIntervalRounding) obj;
            return Objects.equals(interval, other.interval) && Objects.equals(timeZone, other.timeZone);
        }
    }

    public static class Streams {

        public static void write(Rounding rounding, StreamOutput out) throws IOException {
            out.writeByte(rounding.id());
            rounding.writeTo(out);
        }

        public static Rounding read(StreamInput in) throws IOException {
            Rounding rounding = null;
            byte id = in.readByte();
            switch (id) {
                case TimeUnitRounding.ID: rounding = new TimeUnitRounding(); break;
                case TimeIntervalRounding.ID: rounding = new TimeIntervalRounding(); break;
                default: throw new ElasticsearchException("unknown rounding id [" + id + "]");
            }
            rounding.readFrom(in);
            return rounding;
        }

    }

}
