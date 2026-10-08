<?php

declare(strict_types=1);

namespace Maggie\Core\Observability;

use ApiPlatform\Metadata\Exception\HttpExceptionInterface as ApiPlatformHttpException;
use ApiPlatform\Metadata\Exception\ProblemExceptionInterface;
use Sentry\Event;
use Sentry\EventHint;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Symfony\Component\Security\Core\Exception\AccessDeniedException;
use Symfony\Component\Security\Core\Exception\AuthenticationException;
use Symfony\Component\Security\Core\Exception\AuthenticationServiceException;

/**
 * `before_send` of the Sentry SDK: drops an event whose exception becomes a
 * 4xx answer. A 404, a refused token or a failed validation is the API doing
 * its job; what reaches GlitchTip — then Linear, as an Urgent bug — must be a
 * 5xx or an error nothing handled.
 *
 * The status is the one the client receives: Symfony's HTTP exceptions, API
 * Platform's own and its `exception_to_status` map (a serializer error is a
 * 400 there), and the security exceptions the firewall turns into 401/403.
 */
final readonly class SentryEventFilter
{
    /**
     * @param array<class-string, int> $exceptionToStatus
     */
    public function __construct(
        #[Autowire(param: 'api_platform.exception_to_status')]
        private array $exceptionToStatus = [],
    ) {
    }

    public function __invoke(Event $event, ?EventHint $hint): ?Event
    {
        $exception = $hint?->exception;

        if ($exception instanceof \Throwable && $this->isClientError($exception)) {
            return null;
        }

        return $event;
    }

    private function isClientError(\Throwable $exception): bool
    {
        $status = $this->statusOf($exception);

        return null !== $status && $status >= 400 && $status < 500;
    }

    private function statusOf(\Throwable $exception): ?int
    {
        if ($exception instanceof HttpExceptionInterface || $exception instanceof ApiPlatformHttpException) {
            return $exception->getStatusCode();
        }

        if ($exception instanceof ProblemExceptionInterface && null !== $exception->getStatus()) {
            return $exception->getStatus();
        }

        // A broken user provider is the server's fault, not the client's.
        if ($exception instanceof AuthenticationServiceException) {
            return null;
        }

        if ($exception instanceof AuthenticationException) {
            return 401;
        }

        if ($exception instanceof AccessDeniedException) {
            return 403;
        }

        foreach ($this->exceptionToStatus as $class => $status) {
            if (is_a($exception, $class)) {
                return $status;
            }
        }

        return null;
    }
}
