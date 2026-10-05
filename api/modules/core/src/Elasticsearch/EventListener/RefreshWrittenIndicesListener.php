<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\EventListener;

use Maggie\Core\Elasticsearch\IndexManager;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Symfony\Component\HttpKernel\KernelEvents;

final class RefreshWrittenIndicesListener
{
    public function __construct(
        private readonly IndexManager $indexManager,
    ) {
    }

    #[AsEventListener(event: KernelEvents::RESPONSE)]
    public function onKernelResponse(ResponseEvent $event): void
    {
        if (!$event->isMainRequest()) {
            return;
        }

        $this->indexManager->refreshWritten();
    }
}
