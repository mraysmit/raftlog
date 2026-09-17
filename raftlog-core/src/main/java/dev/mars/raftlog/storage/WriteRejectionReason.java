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

/** Stable categories for understood storage write rejections. */
public enum WriteRejectionReason {
    PAYLOAD_TOO_LARGE,
    INSUFFICIENT_DISK_SPACE,
    /** The log has not been replayed since open, or a failed write left its tail unknown. */
    LOG_STATE_UNKNOWN,
    /** An append does not continue the log at the next index, or the batch is not contiguous. */
    INDEX_NOT_CONTIGUOUS,
    /** An entry's term is lower than the term before it, or a metadata term went backwards. */
    TERM_REGRESSION,
    /** A vote was changed within the same term. */
    VOTE_CHANGED,
    /** A suffix truncation boundary is below 1 or beyond the end of the log. */
    INVALID_TRUNCATION
}
