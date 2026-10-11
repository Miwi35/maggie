<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

use Psr\Log\LoggerInterface;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\Event\TerminateEvent;
use Symfony\Component\HttpKernel\KernelEvents;

/**
 * Records the lines each request runs under the e2e journey it belongs to (spec « Sélection e2e
 * par couverture », part A) — for the nightly's line → journey map.
 *
 * Three locks keep it out of everything else: the service exists only in the `e2e` environment
 * (src/E2e/ is registered under `when@e2e` alone), it does nothing unless E2E_COVERAGE=1, and
 * nothing unless the request carries `X-E2E-Journey` — sent by the journeys, passed on by the
 * agent on its MCP calls.
 *
 * Collection starts as early as the kernel lets a listener in and is written once the response
 * has left (kernel.terminate), so the journey does not wait for the file. What a request hands
 * to the Messenger worker runs in another process, without the header, and is not attributed.
 */
final class JourneyCoverageListener implements EventSubscriberInterface
{
    private ?string $journey = null;

    public function __construct(
        private readonly LineCollector $collector,
        private readonly JourneyCoverageWriter $writer,
        private readonly LoggerInterface $logger,
        private readonly bool $coverageEnabled,
    ) {
    }

    public static function getSubscribedEvents(): array
    {
        return [
            KernelEvents::REQUEST => ['onRequest', 4096],
            KernelEvents::TERMINATE => ['onTerminate', -4096],
        ];
    }

    public function onRequest(RequestEvent $event): void
    {
        if (!$this->coverageEnabled || !$event->isMainRequest()) {
            return;
        }

        $this->journey = Journey::valid($event->getRequest()->headers->get(Journey::HEADER));
        if (null === $this->journey) {
            return;
        }

        try {
            $this->collector->start();
        } catch (\Throwable $e) {
            $this->journey = null;
            $this->logger->warning('E2E coverage could not start: {message}', ['message' => $e->getMessage()]);
        }
    }

    public function onTerminate(TerminateEvent $event): void
    {
        if (null === $this->journey) {
            return;
        }

        $journey = $this->journey;
        $this->journey = null;

        try {
            $this->writer->record($journey, $this->collector->stop());
        } catch (\Throwable $e) {
            // A lost sample makes the map a little less precise; a failed request would fail a journey.
            $this->logger->warning('E2E coverage of a request was not recorded: {message}', ['message' => $e->getMessage(), 'journey' => $journey]);
        }
    }
}
