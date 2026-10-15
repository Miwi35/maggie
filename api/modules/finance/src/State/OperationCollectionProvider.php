<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Finance\Entity\RecurringOperation;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/**
 * The operations a view lists: `/api/recurring_operations` the series,
 * `/api/categorization_rules` the others — what each listed before they were
 * one — and `/api/operations` all of them, or what `exists[period]` asks.
 *
 * @implements ProviderInterface<object>
 */
final class OperationCollectionProvider implements ProviderInterface
{
    /** @param ProviderInterface<object> $inner */
    public function __construct(
        #[Autowire(service: ElasticsearchCollectionProvider::class)]
        private readonly ProviderInterface $inner,
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $view = $operation->getExtraProperties()[RecurringOperation::VIEW] ?? null;

        if (null !== $view) {
            $filters = \is_array($context['filters'] ?? null) ? $context['filters'] : [];
            $exists = \is_array($filters['exists'] ?? null) ? $filters['exists'] : [];
            $exists['period'] = RecurringOperation::VIEW_SERIES === $view ? 'true' : 'false';
            $filters['exists'] = $exists;
            $context['filters'] = $filters;
        }

        return $this->inner->provide($operation, $uriVariables, $context);
    }
}
