package com.persiangulfwiki.core.config;

import com.fasterxml.jackson.databind.JavaType;
import com.persiangulfwiki.core.common.dto.ApiResult;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Iterator;

/**
 * Documents {@code ApiResult<T>} as bare {@code T}.
 *
 * <p>Every controller returns the {@code { data, message }} envelope, but showing it on every
 * single response schema buries the part a client actually cares about under two levels of
 * nesting. This converter rewrites the schema for {@code ApiResult<T>} to the schema for
 * {@code T} (and to no schema at all for {@code ApiResult<Void>}, which carries no payload).
 * The envelope itself is documented once, in the API description in {@link OpenApiConfig} —
 * the wire format is unchanged, only its documentation is flattened.
 */
public class ApiResultSchemaConverter implements ModelConverter {

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
            Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());

        if (javaType != null && ApiResult.class.equals(javaType.getRawClass())) {
            JavaType data = javaType.containedTypeCount() > 0 ? javaType.containedType(0) : null;

            // ApiResult<Void> — `data` is always null, so there is nothing left to show
            // once the envelope is dropped. Returning null emits no response schema.
            if (data == null || Void.class.equals(data.getRawClass())
                    || void.class.equals(data.getRawClass())) {
                return null;
            }

            return context.resolve(new AnnotatedType(data)
                    .ctxAnnotations(type.getCtxAnnotations())
                    .parent(type.getParent())
                    .schemaProperty(type.isSchemaProperty())
                    .resolveAsRef(type.isResolveAsRef())
                    .jsonViewAnnotation(type.getJsonViewAnnotation())
                    .propertyName(type.getPropertyName())
                    .skipOverride(true));
        }

        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }
}
