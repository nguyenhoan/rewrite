/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.internal;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.quarkus.gizmo.*;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;

import java.lang.reflect.*;
import java.util.IdentityHashMap;
import java.util.Map;

public class TreeVisitorAdapter {
    private static final Integer classCreationLock = 1;
    private static final Map<ClassLoader, TreeVisitorAdapterClassLoader> classLoaders = new IdentityHashMap<>();

    private TreeVisitorAdapter() {
    }

    public static void unload(ClassLoader parentClassLoader) {
        classLoaders.remove(parentClassLoader);
    }

    public static <Adapted> Adapted adapt(TreeVisitor<?, ?> delegate, Class<Adapted> adaptTo) {
        Timer.Sample timer = Timer.start();

        String adaptedName = delegate.getClass().getName().trim().replace('$', '_') +
                "_" + adaptTo.getSimpleName();

        //noinspection rawtypes
        Class<? extends TreeVisitor> delegateType = adapterDelegateType(delegate);

        TreeVisitorAdapterClassLoader cl;
        synchronized (classLoaders) {
            cl = classLoaders.computeIfAbsent(delegate.getClass().getClassLoader(), TreeVisitorAdapterClassLoader::new);
        }

        if (!cl.hasClass(adaptedName)) {
            synchronized (classCreationLock) {
                if(!cl.hasClass(adaptedName)) {
                    try (ClassCreator creator = ClassCreator.builder()
                            .classOutput(cl)
                            .className(adaptedName)
                            .superClass(adaptTo)
                            .build()) {

                        FieldCreator delegateField = creator.getFieldCreator("delegate", delegateType);
                        delegateField.setModifiers(Modifier.PRIVATE);

                        MethodCreator setDelegate = creator.getMethodCreator("setDelegate", adaptTo, delegateType);
                        setDelegate.setModifiers(Modifier.PUBLIC);
                        setDelegate.writeInstanceField(delegateField.getFieldDescriptor(), setDelegate.getThis(),
                                setDelegate.getMethodParam(0));
                        setDelegate.returnValue(setDelegate.getThis());

                        for (Method method : delegate.getClass().getDeclaredMethods()) {
                            if (method.getName().startsWith("visit") || method.getName().equals("preVisit") || method.getName().equals("postVisit")) {
                                if (method.getName().equals("visitSourceFile")) {
                                    continue;
                                }

                                nextMethod:
                                for (Method adaptToMethod : adaptTo.getMethods()) {
                                    if (method.getName().equals(adaptToMethod.getName()) && method.getParameterCount() == adaptToMethod.getParameterCount() && !Modifier.isFinal(adaptToMethod.getModifiers())) {
                                        Class<?>[] parameterTypes = method.getParameterTypes();
                                        for (int i = 0; i < parameterTypes.length; i++) {
                                            if (!method.getParameterTypes()[i].equals(parameterTypes[i])) {
                                                continue nextMethod;
                                            }
                                        }
                                        break;
                                    }
                                }

                                MethodCreator visitMethod = creator.getMethodCreator(
                                        method.getName(),
                                        method.getReturnType(),
                                        (Object[]) method.getParameterTypes());
                                visitMethod.setModifiers(method.getModifiers());

                                int paramLength = method.getParameters().length;
                                ResultHandle[] args = new ResultHandle[paramLength];
                                for (int i = 0; i < paramLength; i++) {
                                    args[i] = visitMethod.getMethodParam(i);
                                }

                                ResultHandle delegateCall = visitMethod.invokeVirtualMethod(
                                        MethodDescriptor.ofMethod(delegateType, method.getName(),
                                                DescriptorUtils.classToStringRepresentation(method.getReturnType()),
                                                (Object[]) method.getParameterTypes()),
                                        visitMethod.readInstanceField(delegateField.getFieldDescriptor(), visitMethod.getThis()),
                                        args
                                );
                                visitMethod.returnValue(delegateCall);

                                ResultHandle ret = visitMethod.loadNull();
                                visitMethod.returnValue(ret);
                            }
                        }
                    }
                }
            }
        }

        try {
            Class<?> a = cl.loadClass(adaptedName);

            //noinspection unchecked
            Adapted adapted = (Adapted) a.getDeclaredConstructor().newInstance();
            a.getDeclaredMethod("setDelegate", delegateType).invoke(adapted, delegate);
            return adapted;
        } catch (InvocationTargetException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                 ClassNotFoundException e) {
            timer.stop(MetricsHelper.errorTags(Timer.builder("rewrite.visitor.adapt")
                    .tag("adapt.to", adaptTo.getSimpleName()), e).register(Metrics.globalRegistry));
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private static Class<? extends TreeVisitor> adapterDelegateType(TreeVisitor<?, ?> delegate) {
        for (TypeVariable<? extends Class<? extends TreeVisitor>> tp : delegate.getClass().getTypeParameters()) {
            for (Type bound : tp.getBounds()) {
                if (bound instanceof Class && Tree.class.isAssignableFrom((Class<?>) bound)) {
                    return delegate.getClass();
                }
            }
        }

        Class<?> v2 = delegate.getClass();
        Type sup = v2.getGenericSuperclass();
        for (int i = 0; i < 20; i++) {
            if (sup instanceof ParameterizedType) {
                for (Type bound : ((ParameterizedType) sup).getActualTypeArguments()) {
                    if (bound instanceof Class && Tree.class.isAssignableFrom((Class<?>) bound)) {
                        if (delegate.getLanguage() == null) {
                            //noinspection unchecked
                            return (Class<? extends TreeVisitor>) ((ParameterizedType) sup).getRawType();
                        }
                        //noinspection unchecked
                        return (Class<? extends TreeVisitor>) v2;
                    }
                }
                sup = ((ParameterizedType) sup).getRawType();
            } else if (sup instanceof Class) {
                v2 = (Class<?>) sup;
                if (v2.getName().endsWith("IsoVisitor")) {
                    //noinspection unchecked
                    return (Class<? extends TreeVisitor>) v2;
                }
                sup = ((Class<?>) sup).getGenericSuperclass();
            }
        }
        throw new IllegalArgumentException("Expected to find a tree type somewhere in the type parameters of the " +
                "type hierarhcy of visitor " + delegate.getClass().getName());
    }
}