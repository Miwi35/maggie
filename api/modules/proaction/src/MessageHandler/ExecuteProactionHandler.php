<?php

declare(strict_types=1);

namespace Maggie\Proaction\MessageHandler;

use Maggie\Proaction\Entity\ProactionStatus;
use Maggie\Proaction\Message\ExecuteProactionMessage;
use Maggie\Proaction\Repository\ProactionRepository;
use Maggie\Proaction\Service\AgentHubClient;
use Doctrine\ORM\EntityManagerInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ExecuteProactionHandler
{
    public function __construct(
        private readonly ProactionRepository $proactionRepository,
        private readonly AgentHubClient $agentHubClient,
        private readonly EntityManagerInterface $em,
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

            $proaction->setResponse($result['response']);
            $proaction->setStatus(ProactionStatus::Success);
            $proaction->setCompletedAt(new \DateTimeImmutable());
        } catch (\Throwable $e) {
            $this->logger->error('Proaction {id} failed: {error}', [
                'id' => $message->proactionId,
                'error' => $e->getMessage(),
            ]);
            $proaction->setError($e->getMessage());
            $proaction->setStatus(ProactionStatus::Failed);
            $proaction->setCompletedAt(new \DateTimeImmutable());
        }

        $this->em->flush();
    }
}
