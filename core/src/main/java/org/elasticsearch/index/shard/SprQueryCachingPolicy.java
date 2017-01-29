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

package org.elasticsearch.index.shard;

import org.apache.lucene.document.BigIntegerPoint;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.*;

/**
 * @author rahulanishetty
 * @since 30/01/17.
 */
public class SprQueryCachingPolicy implements QueryCachingPolicy {


    public static final List<String> DEFAULT_CLASSES = Collections.unmodifiableList(Arrays.asList(getSimpleName(TermQuery.class),
        getSimpleName(PhraseQuery.class),
        getSimpleName(LegacyNumericRangeQuery.class),
        getSimpleName(BigIntegerPoint.class),
        getSimpleName(LongPoint.class),
        getSimpleName(FloatPoint.class),
        getSimpleName(DoublePoint.class),
        getSimpleName(PointRangeQuery.class)));

    /**
     * NOTE : policy returns should cache true for any class passed via leafClassesToCache or any of anonymous or local classes
     * <p>
     * leafClasses to cache expect lower case simple name of queries {@link Class#getSimpleName()}
     */
    private final Set<String> leafClassesToCache;

    SprQueryCachingPolicy(List<String> leafClassesToCache) {
        if (leafClassesToCache == null || leafClassesToCache.isEmpty()) {
            this.leafClassesToCache = Collections.emptySet();
        } else {
            this.leafClassesToCache = new HashSet<>(leafClassesToCache);
        }
    }

    @Override
    public void onUse(Query query) {
        //do-nothing
    }

    @Override
    public boolean shouldCache(Query query) throws IOException {
        Class<? extends Query> clz = query.getClass();
        if (clz.isAnonymousClass()) {
            return leafClassesToCache.contains(getSimpleNameInLowerCase(clz.getEnclosingClass()));
        }
        return leafClassesToCache.contains(getSimpleNameInLowerCase(clz));
    }

    private static String getSimpleName(Class<?> clz) {
        return clz.getSimpleName();
    }

    private static String getSimpleNameInLowerCase(Class<?> clz) {
        return getSimpleName(clz).toLowerCase(Locale.ROOT);
    }
}
