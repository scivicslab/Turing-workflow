/*
 * Copyright 2025 SCIVICS Lab
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
package com.scivicslab.pojoactor.action;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies state S2.03: @Action annotation is defined with correct retention and target.
 */
@Tag("S_svc.03")
@DisplayName("@Action annotation definition (S2.03)")
public class ActionAnnotationDefinitionTest {

    static class SampleActor {
        @Action("doWork")
        public ActionResult doWork(String args) {
            return new ActionResult(true, "done");
        }

        @Action("getStatus")
        public ActionResult getStatus(String args) {
            return new ActionResult(true, "ok");
        }

        public void notAnAction() {}
    }

    @Test
    @DisplayName("@Action has RUNTIME retention")
    void actionHasRuntimeRetention() {
        Retention retention = Action.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    @DisplayName("@Action targets METHOD only")
    void actionTargetsMethod() {
        Target target = Action.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] types = target.value();
        assertEquals(1, types.length);
        assertEquals(ElementType.METHOD, types[0]);
    }

    @Test
    @DisplayName("@Action is discoverable on annotated method at runtime")
    void actionIsDiscoverableAtRuntime() throws Exception {
        Method method = SampleActor.class.getDeclaredMethod("doWork", String.class);
        Action annotation = method.getAnnotation(Action.class);
        assertNotNull(annotation, "@Action must have RUNTIME retention to be found here");
        assertEquals("doWork", annotation.value());
    }

    @Test
    @DisplayName("@Action value returns the specified action name")
    void actionValueReturnsName() throws Exception {
        Method method = SampleActor.class.getDeclaredMethod("getStatus", String.class);
        Action annotation = method.getAnnotation(Action.class);
        assertNotNull(annotation);
        assertEquals("getStatus", annotation.value());
    }

    @Test
    @DisplayName("methods without @Action return null annotation")
    void methodWithoutActionReturnsNull() throws Exception {
        Method method = SampleActor.class.getDeclaredMethod("notAnAction");
        Action annotation = method.getAnnotation(Action.class);
        assertNull(annotation);
    }

    @Test
    @DisplayName("multiple @Action methods on same class are independently discoverable")
    void multipleActionsOnSameClass() throws Exception {
        Method doWork = SampleActor.class.getDeclaredMethod("doWork", String.class);
        Method getStatus = SampleActor.class.getDeclaredMethod("getStatus", String.class);

        assertEquals("doWork", doWork.getAnnotation(Action.class).value());
        assertEquals("getStatus", getStatus.getAnnotation(Action.class).value());
    }
}
