<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\TagStandardCategoriesCommand;
use Maggie\Finance\UseCase\TagStandardCategories;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class TagStandardCategoriesHandler
{
    public function __construct(
        private readonly TagStandardCategories $tagStandardCategories,
        private readonly UserRepository $userRepository,
    ) {
    }

    /** @return array<string, string> key => the name of the category that received it */
    public function __invoke(TagStandardCategoriesCommand $command): array
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return array_map(
            static fn (Category $category) => $category->getName(),
            $this->tagStandardCategories->execute($user),
        );
    }
}
