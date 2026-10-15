<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\QualifyingOperation;
use Maggie\Finance\Message\RequalifyTransactionsCommand;
use Maggie\Finance\UseCase\RequalifyTransactions;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RequalifyTransactionsHandler
{
    public function __construct(
        private readonly RequalifyTransactions $requalifyTransactions,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(RequalifyTransactionsCommand $command): int
    {
        $operation = $this->em->find($command->operationClass, $command->operationId);

        // Deleted in between: its lines were let go with it.
        if (!$operation instanceof QualifyingOperation) {
            return 0;
        }

        return $this->requalifyTransactions->execute($operation);
    }
}
