/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.syndesis.server.endpoint.v1.handler.meta;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.types.ArraySchema;
import io.syndesis.common.model.DataShape;
import io.syndesis.common.model.DataShapeKinds;
import io.syndesis.common.model.connection.DynamicActionMetadata;
import io.syndesis.common.model.integration.StepKind;
import io.syndesis.common.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * @author Christoph Deppisch
 */
class SplitMetadataHandler implements StepMetadataHandler {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(SplitMetadataHandler.class);

    @Override
    public boolean canHandle(StepKind kind) {
        return StepKind.split.equals(kind);
    }

    @Override
    public DynamicActionMetadata handle(DynamicActionMetadata metadata) {
        try {
            if (metadata.outputShape() != null) {
                DataShape dataShape = new DataShape.Builder()
                                            .createFrom(metadata.outputShape())
                                            .decompress()
                                            .build();

                Optional<DataShape> singleElementShape = dataShape.findVariantByMeta(VARIANT_METADATA_KEY, VARIANT_ELEMENT);

                if (singleElementShape.isPresent()) {
                    if (dataShape.equals(singleElementShape.get())) {
                        return new DynamicActionMetadata.Builder()
                                        .createFrom(metadata)
                                        .outputShape(singleElementShape.get())
                                .build();
                    } else {
                        return new DynamicActionMetadata.Builder()
                                .createFrom(metadata)
                                .outputShape(new DataShape.Builder()
                                        .createFrom(singleElementShape.get())
                                        .addAllVariants(extractVariants(dataShape, singleElementShape.get(), VARIANT_ELEMENT))
                                        .build())
                                .build();
                    }
                }

                DataShape collectionShape = dataShape.findVariantByMeta(VARIANT_METADATA_KEY, VARIANT_COLLECTION).orElse(dataShape);

                String specification = collectionShape.getSpecification();
                if (StringUtils.hasText(specification)) {
                    if (collectionShape.getKind() == DataShapeKinds.JSON_SCHEMA) {
                        JsonSchema schema = Json.reader().forType(JsonSchema.class).readValue(specification);

                        if (schema.isArraySchema()) {
                            ArraySchema.Items items = ((ArraySchema) schema).getItems();
                            JsonSchema itemSchema = items.asSingleItems().getSchema();
                            itemSchema.set$schema(schema.get$schema());
                            if (items.isSingleItems()) {
                                return new DynamicActionMetadata.Builder()
                                        .createFrom(metadata)
                                        .outputShape(new DataShape.Builder().createFrom(collectionShape)
                                                .putMetadata(VARIANT_METADATA_KEY, VARIANT_ELEMENT)
                                                .specification(Json.writer().writeValueAsString(itemSchema))
                                                .addAllVariants(extractVariants(dataShape, collectionShape, VARIANT_COLLECTION))
                                                .build())
                                        .build();
                            }
                        } else {
                            return new DynamicActionMetadata.Builder()
                                    .createFrom(metadata)
                                    .outputShape(new DataShape.Builder().createFrom(collectionShape)
                                            .putMetadata(VARIANT_METADATA_KEY, VARIANT_ELEMENT)
                                            .build())
                                    .build();
                        }
                    } else if (collectionShape.getKind() == DataShapeKinds.JSON_INSTANCE) {
                        if (isJsonInstanceArraySpec(specification)) {
                            List<Object> items = Json.reader().forType(List.class).readValue(specification);
                            if (!items.isEmpty()) {
                                return new DynamicActionMetadata.Builder()
                                        .createFrom(metadata)
                                        .outputShape(new DataShape.Builder().createFrom(collectionShape)
                                                .putMetadata(VARIANT_METADATA_KEY, VARIANT_ELEMENT)
                                                .specification(Json.writer().writeValueAsString(items.get(0)))
                                                .addAllVariants(extractVariants(dataShape, collectionShape, VARIANT_COLLECTION))
                                                .build())
                                        .build();
                            }
                        } else {
                            return new DynamicActionMetadata.Builder()
                                    .createFrom(metadata)
                                    .outputShape(new DataShape.Builder().createFrom(collectionShape)
                                            .putMetadata(VARIANT_METADATA_KEY, VARIANT_ELEMENT)
                                            .build())
                                    .build();
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Unable to read output data shape on dynamic meta data inspection", e);
        }

        return DynamicActionMetadata.NOTHING;
    }
}
