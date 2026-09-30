<?php

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class GoogleCalendarWebhookController
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
        private readonly string $googleWebhookToken,
    ) {
    }

    #[Route('/api/calendar/google/webhook', name: 'google_calendar_webhook', methods: ['POST'])]
    public function __invoke(Request $request): Response
    {
        $channelId = $request->headers->get('X-Goog-Channel-ID');
        $resourceState = $request->headers->get('X-Goog-Resource-State');
        $token = $request->headers->get('X-Goog-Channel-Token');

        // Verify token
        if ($token !== $this->googleWebhookToken) {
            $this->logger->warning('Invalid webhook token received');

            return new Response('', Response::HTTP_FORBIDDEN);
        }

        // Initial sync handshake
        if ('sync' === $resourceState) {
            return new Response('', Response::HTTP_OK);
        }

        if ('exists' !== $resourceState) {
            return new Response('', Response::HTTP_OK);
        }

        if (null === $channelId) {
            return new Response('', Response::HTTP_BAD_REQUEST);
        }

        $agenda = $this->agendaRepository->findByGoogleWatchChannelId($channelId);
        if (null === $agenda) {
            $this->logger->warning('Webhook received for unknown channel: {channelId}', [
                'channelId' => $channelId,
            ]);

            return new Response('', Response::HTTP_OK);
        }

        $this->messageBus->dispatch(new PullFromGoogleCommand(
            agendaId: (string) $agenda->getId(),
        ));

        return new Response('', Response::HTTP_OK);
    }
}
