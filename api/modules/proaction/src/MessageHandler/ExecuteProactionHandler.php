<?php

declare(strict_types=1);

namespace Maggie\Proaction\MessageHandler;

use Maggie\Proaction\Entity\ProactionStatus;
use Maggie\Proaction\Message\ExecuteProactionMessage;
use Maggie\Proaction\Message\UpdateProactionCommand;
use Maggie\Proaction\Repository\ProactionRepository;
use Maggie\Proaction\Service\AgentHubClient;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class ExecuteProactionHandler
{
    public function __construct(
        private readonly ProactionRepository $proactionRepository,
        private readonly AgentHubClient $agentHubClient,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function __invoke(ExecuteProactionMessage $message): void
    {
        $proaction = $this->proactionRepository->find($message->proactionId);

        if ($proaction === null) {
            $this->logger->warning('Proaction not found: {id}', ['id' => $message->proactionId]);
            return;
        }

        if ($proaction->getStatus() !== ProactionStatus::Running) {
            $this->logger->info('Proaction {id} is not in running state, skipping', ['id' => $message->proactionId]);
            return;
        }

        try {
            $result = $this->agentHubClient->executeProaction(
                (string) $proaction->getUser()->getId(),
                $proaction->getPrompt(),
            );

            $this->messageBus->dispatch(new UpdateProactionCommand(
                proactionId: $message->proactionId,
                status: ProactionStatus::Success->value,
                response: $result['response'],
                completedAt: new \DateTimeImmutable(),
            ));
        } catch (\Throwable $e) {
            $this->logger->error('Proaction {id} failed: {error}', [
                'id' => $message->proactionId,
                'error' => $e->getMessage(),
            ]);

            $this->messageBus->dispatch(new UpdateProactionCommand(
                proactionId: $message->proactionId,
                status: ProactionStatus::Failed->value,
                error: $e->getMessage(),
                completedAt: new \DateTimeImmutable(),
            ));
        }
    }
}
