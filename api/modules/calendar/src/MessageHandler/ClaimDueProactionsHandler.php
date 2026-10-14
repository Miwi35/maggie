<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\ClaimDueProactionsCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ClaimDueProactionsHandler
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly UserRepository $userRepository,
    ) {
    }

    /** @return Proaction[] */
    public function __invoke(ClaimDueProactionsCommand $command): array
    {
        $user = null !== $command->userId
            ? ($this->userRepository->find($command->userId) ?? throw new \DomainException('No user found.'))
            : null;

        return $this->proactions->claimDue($user, $command->limit, $command->proactionId);
    }
}
