/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.action;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Compile-time annotation processor for @Action annotation.
 *
 * <p>This processor validates that {@code @Action} is only used on:</p>
 * <ul>
 *   <li>Subclasses of base classes registered via {@link ActionBaseClassProvider} SPI</li>
 *   <li>Interface default methods (mixin pattern)</li>
 * </ul>
 *
 * <p>Base classes are discovered at compile time via {@link ServiceLoader}.
 * Libraries that define action-compatible base classes (e.g., Turing-workflow's IIActorRef)
 * register themselves by implementing {@link ActionBaseClassProvider} and listing it in
 * {@code META-INF/services/com.scivicslab.pojoactor.action.ActionBaseClassProvider}.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ActionAnnotationProcessor extends AbstractProcessor {

    private Set<String> baseClassNames;

    private Set<String> loadBaseClassNames() {
        Set<String> names = new HashSet<>();
        for (ActionBaseClassProvider provider : ServiceLoader.load(ActionBaseClassProvider.class)) {
            names.add(provider.getBaseClassName());
        }
        return names;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (baseClassNames == null) {
            baseClassNames = loadBaseClassNames();
        }

        if (baseClassNames.isEmpty()) {
            // No base classes registered — skip validation
            return false;
        }

        // Check 1: @Action on non-base-class subclasses (allow interface default methods for mixin pattern)
        for (Element element : roundEnv.getElementsAnnotatedWith(Action.class)) {
            Element enclosingElement = element.getEnclosingElement();
            if (!(enclosingElement instanceof TypeElement)) {
                continue;
            }

            TypeElement enclosingClass = (TypeElement) enclosingElement;

            boolean isInterface = enclosingClass.getKind() == ElementKind.INTERFACE;
            boolean isBaseClassSubclass = extendsBaseClass(enclosingClass);

            if (!isInterface && !isBaseClassSubclass) {
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    String.format(
                        "@Action annotation can only be used on methods in registered base class subclasses " +
                        "or interface default methods (mixin pattern). " +
                        "Class '%s' does not extend any registered base class. " +
                        "Registered base classes: %s",
                        enclosingClass.getQualifiedName(),
                        baseClassNames
                    ),
                    element
                );
            }
        }

        // Check 2: callByActionName override in base class subclasses
        for (Element element : roundEnv.getRootElements()) {
            if (element instanceof TypeElement typeElement) {
                if (extendsBaseClass(typeElement) && !isBaseClassItself(typeElement)) {
                    checkForCallByActionNameOverride(typeElement);
                }
            }
        }

        return false;
    }

    private void checkForCallByActionNameOverride(TypeElement typeElement) {
        for (Element member : typeElement.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) member;
                if (isCallByActionNameMethod(method)) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "Overriding callByActionName() is deprecated. " +
                        "Use @Action annotation on methods instead.",
                        method
                    );
                }
            }
        }
    }

    private boolean isCallByActionNameMethod(ExecutableElement method) {
        if (!method.getSimpleName().toString().equals("callByActionName")) {
            return false;
        }
        var params = method.getParameters();
        if (params.size() != 2) {
            return false;
        }
        return params.get(0).asType().toString().equals("java.lang.String")
            && params.get(1).asType().toString().equals("java.lang.String");
    }

    private boolean extendsBaseClass(TypeElement typeElement) {
        TypeMirror superClass = typeElement.getSuperclass();

        while (superClass != null && !superClass.toString().equals("java.lang.Object")) {
            String superClassName = superClass.toString();

            if (superClassName.contains("<")) {
                superClassName = superClassName.substring(0, superClassName.indexOf('<'));
            }

            if (baseClassNames.contains(superClassName)) {
                return true;
            }

            Element superElement = processingEnv.getTypeUtils().asElement(superClass);
            if (superElement instanceof TypeElement) {
                superClass = ((TypeElement) superElement).getSuperclass();
            } else {
                break;
            }
        }

        return false;
    }

    private boolean isBaseClassItself(TypeElement typeElement) {
        return baseClassNames.contains(typeElement.getQualifiedName().toString());
    }
}
