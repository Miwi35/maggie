<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\DeleteCategoryCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\DeleteCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class DeleteCategoryHandler
{
    public function __construct(
        private readonly DeleteCategory $deleteCategory,
        private readonly CategoryRepository $categoryRepository,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    public function __invoke(DeleteCategoryCommand $command): void
    {
        $category = $this->categoryRepository->find($command->categoryId)
            ?? throw new \DomainException("Category not found: {$command->categoryId}");

        // The database cascades remove sub-categories, envelopes and rules without any command of their own.
        $cascaded = $this->cascadedDocuments($category);

        $this->deleteCategory->execute($category);

        foreach ($cascaded as [$indexName, $documentId]) {
            $this->messageBus->dispatch(new DeleteDocumentCommand(indexName: $indexName, documentId: $documentId));
        }
    }

    /**
     * @return list<array{string, string}> [index, id] of everything the category takes down with it
     */
    private function cascadedDocuments(Category $category): array
    {
        $documents = [];

        foreach ($this->envelopeRepository->findBy(['category' => $category]) as $envelope) {
            $documents[] = ['envelopes', (string) $envelope->getId()];
        }
        foreach ($this->ruleRepository->findBy(['category' => $category]) as $rule) {
            $documents[] = ['categorization_rules', (string) $rule->getId()];
        }
        foreach ($this->categoryRepository->findBy(['parent' => $category]) as $child) {
            $documents[] = ['categories', (string) $child->getId()];
            array_push($documents, ...$this->cascadedDocuments($child));
        }

        return $documents;
    }
}
