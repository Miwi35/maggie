<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Message\RenameUtensilCommand;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Cookbook\UseCase\RenameUtensil;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RenameUtensilHandler
{
    public function __construct(
        private readonly RenameUtensil $renameUtensil,
        private readonly UtensilRepository $utensilRepository,
    ) {
    }

    public function __invoke(RenameUtensilCommand $command): Utensil
    {
        $utensil = $this->utensilRepository->find($command->utensilId)
            ?? throw new \DomainException("Utensil not found: {$command->utensilId}");

        return $this->renameUtensil->execute($utensil, $command->name);
    }
}
