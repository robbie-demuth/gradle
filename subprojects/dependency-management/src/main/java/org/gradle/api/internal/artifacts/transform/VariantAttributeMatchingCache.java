/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.Maps;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.internal.artifacts.VariantTransformRegistry;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.internal.component.model.AttributeMatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class VariantAttributeMatchingCache {
    private final VariantTransformRegistry variantTransforms;
    private final AttributesSchemaInternal schema;
    private final ImmutableAttributesFactory attributesFactory;
    private final Map<AttributeContainer, AttributeSpecificCache> attributeSpecificCache = Maps.newConcurrentMap();

    public VariantAttributeMatchingCache(VariantTransformRegistry variantTransforms, AttributesSchemaInternal schema, ImmutableAttributesFactory attributesFactory) {
        this.variantTransforms = variantTransforms;
        this.schema = schema;
        this.attributesFactory = attributesFactory;
    }

    public <T extends HasAttributes> List<T> selectMatches(Collection<T> candidates, AttributeContainerInternal requested) {
        if (requested.isEmpty() && candidates.size() == 1) {
            return Collections.singletonList(candidates.iterator().next());
        }

        List<AttributeContainer> candidateAttributes = new ArrayList<AttributeContainer>(candidates.size());
        for (T candidate : candidates) {
            candidateAttributes.add(candidate.getAttributes());
        }

        AttributeSpecificCache toCache = getCache(requested);
        List<AttributeContainer> matching = toCache.matching.get(candidateAttributes);
        if (matching == null) {
            matching = schema.ignoreAdditionalProducerAttributes().matches(candidateAttributes, requested);
            toCache.matching.put(candidateAttributes, matching);
        }
        if (matching.size() == 0) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<T>(matching.size());
        for (T candidate : candidates) {
            if (matching.contains(candidate.getAttributes())) {
                result.add(candidate);
            }
        }
        return result;
    }

    public void collectConsumerVariants(AttributeContainerInternal actual, AttributeContainerInternal requested, ConsumerVariantMatchResult result) {
        AttributeSpecificCache toCache = getCache(requested);
        ConsumerVariantMatchResult cachedResult = toCache.transforms.get(actual);
        if (cachedResult == null) {
            cachedResult = new ConsumerVariantMatchResult();
            findProducersFor(actual, requested, cachedResult);
            toCache.transforms.put(actual, cachedResult);
        }
        cachedResult.applyTo(result);
    }

    private void findProducersFor(AttributeContainerInternal actual, AttributeContainerInternal requested, ConsumerVariantMatchResult result) {
        // Prefer direct transformation over indirect transformation
        List<VariantTransformRegistry.Registration> candidates = new ArrayList<VariantTransformRegistry.Registration>();
        for (VariantTransformRegistry.Registration transform : variantTransforms.getTransforms()) {
            if (matchAttributes(transform.getTo(), requested, false)) {
                if (matchAttributes(actual, transform.getFrom(), true)) {
                    ImmutableAttributes variantAttributes = attributesFactory.concat(actual.asImmutable(), transform.getTo().asImmutable());
                    result.matched(actual, variantAttributes, transform.getArtifactTransform(), 1);
                }
                candidates.add(transform);
            }
        }
        if (result.hasMatches()) {
            return;
        }

        for (final VariantTransformRegistry.Registration candidate : candidates) {
            ConsumerVariantMatchResult inputVariants = new ConsumerVariantMatchResult();
            collectConsumerVariants(actual, candidate.getFrom(), inputVariants);
            for (final ConsumerVariantMatchResult.ConsumerVariant inputVariant : inputVariants.getMatches()) {
                ImmutableAttributes variantAttributes = attributesFactory.concat(inputVariant.attributes.asImmutable(), candidate.getTo().asImmutable());
                Transformer<List<File>, File> transformer = new Transformer<List<File>, File>() {
                    @Override
                    public List<File> transform(File file) {
                        List<File> result = new ArrayList<File>();
                        for (File intermediate : inputVariant.transformer.transform(file)) {
                            result.addAll(candidate.getArtifactTransform().transform(intermediate));
                        }
                        return result;
                    }
                };
                result.matched(actual, variantAttributes, transformer, inputVariant.depth + 1);
            }
        }
    }

    private AttributeSpecificCache getCache(AttributeContainer attributes) {
        AttributeSpecificCache cache = attributeSpecificCache.get(attributes);
        if (cache == null) {
            cache = new AttributeSpecificCache();
            attributeSpecificCache.put(attributes, cache);
        }
        return cache;
    }

    private boolean matchAttributes(AttributeContainer actual, AttributeContainer requested, boolean ignoreAdditionalActualAttributes) {
        Map<AttributeContainer, Boolean> cache;
        AttributeMatcher schemaToMatchOn;
        if (ignoreAdditionalActualAttributes) {
            if (requested.isEmpty()) {
                return true;
            }
            schemaToMatchOn = schema.ignoreAdditionalProducerAttributes();
            cache = getCache(requested).ignoreExtraActual;
        } else { // ignore additional requested
            if (actual.isEmpty()) {
                return true;
            }
            schemaToMatchOn = schema.ignoreAdditionalConsumerAttributes();
            cache = getCache(requested).ignoreExtraRequested;
        }

        Boolean match = cache.get(actual);
        if (match == null) {
            match = schemaToMatchOn.isMatching(actual, requested);
            cache.put(actual, match);
        }
        return match;
    }

    public List<ResolvedArtifact> getTransformedArtifacts(ResolvedArtifact actual, AttributeContainer requested) {
        return getCache(requested).transformedArtifacts.get(actual);
    }

    public void putTransformedArtifact(ResolvedArtifact actual, AttributeContainer requested, List<ResolvedArtifact> transformResults) {
        getCache(requested).transformedArtifacts.put(actual, transformResults);
    }

    public List<File> getTransformedFile(File file, AttributeContainer requested) {
        return getCache(requested).transformedFiles.get(file);
    }

    public void putTransformedFile(File file, AttributeContainer requested, List<File> transformResults) {
        getCache(requested).transformedFiles.put(file, transformResults);
    }

    private static class AttributeSpecificCache {
        private final Map<AttributeContainer, Boolean> ignoreExtraRequested = Maps.newConcurrentMap();
        private final Map<AttributeContainer, Boolean> ignoreExtraActual = Maps.newConcurrentMap();
        private final Map<AttributeContainer, ConsumerVariantMatchResult> transforms = Maps.newConcurrentMap();
        private final Map<File, List<File>> transformedFiles = Maps.newConcurrentMap();
        private final Map<ResolvedArtifact, List<ResolvedArtifact>> transformedArtifacts = Maps.newConcurrentMap();
        private final Map<List<AttributeContainer>, List<AttributeContainer>> matching = Maps.newConcurrentMap();
    }
}
