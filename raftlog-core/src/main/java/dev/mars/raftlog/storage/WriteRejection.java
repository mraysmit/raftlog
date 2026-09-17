/*
 * Copyright 2026 Mark Andrew Ray-Smith
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
package dev.mars.raftlog.storage;

/**
 * Implemented by exceptions for understood write rejections.
 * <p>
 * For a multi-record append, {@link #reason()} describes the record that was
 * rejected. An implementation may already have written earlier records in the
 * same batch unless its operation contract states that validation is atomic.
 */
public interface WriteRejection {
    WriteRejectionReason reason();
}
