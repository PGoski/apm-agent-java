/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018-2019 Elastic and contributors
 * %%
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
 * #L%
 */
package co.elastic.apm.agent.impl;

import co.elastic.apm.agent.impl.payload.ProcessFactory;
import co.elastic.apm.agent.impl.payload.ProcessInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ProcessFactoryTest {

    @Test
    void testProcessInformationForLegacyVm() {
        ProcessInfo proc = ProcessFactory.ForLegacyVM.INSTANCE.getProcessInformation();
        assertSoftly(softly -> {
            softly.assertThat(proc.getPid()).isNotEqualTo(0);
            softly.assertThat(proc.getTitle()).contains("java");
        });
    }

    @Test
    void testProcessInformationForCurrentVm() {
        ProcessInfo proc = ProcessFactory.ForCurrentVM.INSTANCE.getProcessInformation();
        assertSoftly(softly -> {
            softly.assertThat(proc.getPid()).isNotEqualTo(0);
            softly.assertThat(proc.getPpid()).isNotNull();
            softly.assertThat(proc.getTitle()).contains("java");
        });
    }
}
