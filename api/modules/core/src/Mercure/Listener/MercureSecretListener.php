<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure\Listener;

use Maggie\Core\Mercure\MercureSecretGuard;
use Symfony\Component\Console\ConsoleEvents;
use Symfony\Component\Console\Event\ConsoleCommandEvent;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\KernelEvents;

/**
 * Web and worker (messenger:consume is a console command) both refuse to run
 * with a Mercure secret too short to sign with. The failed requests are what
 * the post-deploy smoke test catches to roll back.
 */
final class MercureSecretListener
{
    public function __construct(
        private readonly MercureSecretGuard $guard,
    ) {
    }

    #[AsEventListener(event: KernelEvents::REQUEST, priority: 256)]
    public function onKernelRequest(RequestEvent $event): void
    {
        if (!$event->isMainRequest()) {
            return;
        }

        $this->guard->assertValid();
    }

    #[AsEventListener(event: ConsoleEvents::COMMAND)]
    public function onConsoleCommand(ConsoleCommandEvent $event): void
    {
        $this->guard->assertValid();
    }
}
