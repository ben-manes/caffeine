package com.github.benmanes.caffeine.cache.node;

import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;

/**
 * Interfaz general que unifica KeyContext y ValueContext.
 * Cumple con DIP e ISP simultáneamente.
 */
public interface INodeContext extends KeyContext, ValueContext {
    boolean isBaseClass();
    MethodSpec.Builder getConstructorByKey();
    MethodSpec.Builder getConstructorByKeyRef();
    Modifier[] publicFinalModifiers();
}