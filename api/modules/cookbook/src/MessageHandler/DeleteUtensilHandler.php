<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteUtensilCommand;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Cookbook\UseCase\DeleteUtensil;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteUtensilHandler
{
    public function __construct(
        private readonly DeleteUtensil $deleteUtensil,
        private readonly UtensilRepository $utensilRepository,
    ) {
    }

    public function __invoke(DeleteUtensilCommand $command): void
    {
        $utensil = $this->utensilRepository->find($command->utensilId)
            ?? throw new \DomainException("Utensil not found: {$command->utensilId}");

        $this->deleteUtensil->execute($utensil);
    }
}
