/*
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
package io.prestosql.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.prestosql.client.ErrorLocation;
import io.prestosql.execution.ExecutionFailureInfo;
import io.prestosql.execution.Failure;
import io.prestosql.spi.ErrorCode;
import io.prestosql.spi.ErrorCodeSupplier;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.PrestoTransportException;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.sql.analyzer.SemanticErrorCode;
import io.prestosql.sql.analyzer.SemanticException;
import io.prestosql.sql.parser.ParsingException;
import io.prestosql.sql.tree.NodeLocation;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Functions.toStringFunction;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.newIdentityHashSet;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.StandardErrorCode.SYNTAX_ERROR;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

public final class Failures
{
    private static final String NODE_CRASHED_ERROR = "The node may have crashed or be under too much load. " +
            "This is probably a transient issue, so please retry your query in a few minutes.";

    public static final String WORKER_NODE_ERROR = "Encountered too many errors talking to a worker node. " + NODE_CRASHED_ERROR;

    public static final String REMOTE_TASK_MISMATCH_ERROR = "Could not communicate with the remote task. " + NODE_CRASHED_ERROR;

    private Failures() {}

    public static ExecutionFailureInfo toFailure(Throwable failure)
    {
        return toFailure(failure, newIdentityHashSet());
    }

    public static void checkCondition(boolean condition, ErrorCodeSupplier errorCode, String formatString, Object... args)
    {
        if (!condition) {
            throw new PrestoException(errorCode, format(formatString, args));
        }
    }

    public static List<ExecutionFailureInfo> toFailures(Collection<? extends Throwable> failures)
    {
        return failures.stream()
                .map(Failures::toFailure)
                .collect(toImmutableList());
    }

    private static ExecutionFailureInfo toFailure(Throwable throwable, Set<Throwable> seenFailures)
    {
        if (throwable == null) {
            return null;
        }

        String type;
        HostAddress remoteHost = null;
        SemanticErrorCode semanticErrorCode = null;
        if (throwable instanceof Failure) {
            type = ((Failure) throwable).getType();
        }
        else {
            Class<?> clazz = throwable.getClass();
            type = firstNonNull(clazz.getCanonicalName(), clazz.getName());
        }
        if (throwable instanceof PrestoTransportException) {
            remoteHost = ((PrestoTransportException) throwable).getRemoteHost();
        }
        if (throwable instanceof SemanticException) {
            semanticErrorCode = ((SemanticException) throwable).getCode();
        }

        if (seenFailures.contains(throwable)) {
            return new ExecutionFailureInfo(
                    type,
                    "[cyclic] " + throwable.getMessage(),
                    null,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    null,
                    GENERIC_INTERNAL_ERROR.toErrorCode(),
                    Optional.ofNullable(semanticErrorCode),
                    remoteHost);
        }
        seenFailures.add(throwable);

        ExecutionFailureInfo cause = toFailure(throwable.getCause(), seenFailures);
        ErrorCode errorCode = toErrorCode(throwable);
        if (errorCode == null) {
            if (cause == null) {
                errorCode = GENERIC_INTERNAL_ERROR.toErrorCode();
            }
            else {
                errorCode = cause.getErrorCode();
            }
        }

        return new ExecutionFailureInfo(
                type,
                throwable.getMessage(),
                cause,
                Arrays.stream(throwable.getSuppressed())
                        .map(failure -> toFailure(failure, seenFailures))
                        .collect(toImmutableList()),
                Lists.transform(asList(throwable.getStackTrace()), toStringFunction()),
                getErrorLocation(throwable),
                errorCode,
                Optional.ofNullable(semanticErrorCode),
                remoteHost);
    }

    @Nullable
    private static ErrorLocation getErrorLocation(Throwable throwable)
    {
        // TODO: this is a big hack
        if (throwable instanceof ParsingException) {
            ParsingException e = (ParsingException) throwable;
            return new ErrorLocation(e.getLineNumber(), e.getColumnNumber());
        }
        if (throwable instanceof SemanticException) {
            SemanticException e = (SemanticException) throwable;
            if (e.getNode().getLocation().isPresent()) {
                NodeLocation nodeLocation = e.getNode().getLocation().get();
                return new ErrorLocation(nodeLocation.getLineNumber(), nodeLocation.getColumnNumber());
            }
        }
        return null;
    }

    @Nullable
    private static ErrorCode toErrorCode(Throwable throwable)
    {
        requireNonNull(throwable);

        if (throwable instanceof PrestoException) {
            return ((PrestoException) throwable).getErrorCode();
        }
        if (throwable instanceof Failure && ((Failure) throwable).getErrorCode() != null) {
            return ((Failure) throwable).getErrorCode();
        }
        if (throwable instanceof ParsingException || throwable instanceof SemanticException) {
            return SYNTAX_ERROR.toErrorCode();
        }
        return null;
    }

    public static PrestoException internalError(Throwable t)
    {
        throwIfInstanceOf(t, Error.class);
        throwIfInstanceOf(t, PrestoException.class);
        return new PrestoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, t);
    }
}
