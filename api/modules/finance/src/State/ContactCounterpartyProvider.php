<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Doctrine\Orm\State\CollectionProvider;
use ApiPlatform\Doctrine\Orm\State\ItemProvider;
use ApiPlatform\Metadata\CollectionOperationInterface;
use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\UseCase\MeasureCounterpartyUsage;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/**
 * The counterparties from Doctrine — scoped to the user and filtered by
 * `contact` like any Doctrine resource —, each with the number of transfers it
 * recognises and the date of the last one.
 *
 * @implements ProviderInterface<ContactCounterparty>
 */
final class ContactCounterpartyProvider implements ProviderInterface
{
    /**
     * @param ProviderInterface<object> $collectionProvider
     * @param ProviderInterface<object> $itemProvider
     */
    public function __construct(
        #[Autowire(service: CollectionProvider::class)]
        private readonly ProviderInterface $collectionProvider,
        #[Autowire(service: ItemProvider::class)]
        private readonly ProviderInterface $itemProvider,
        private readonly MeasureCounterpartyUsage $usage,
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        if ($operation instanceof CollectionOperationInterface) {
            $result = $this->collectionProvider->provide($operation, $uriVariables, $context);
            if (is_iterable($result)) {
                /* @var iterable<ContactCounterparty> $result */
                $this->usage->fill($result);
            }

            return $result;
        }

        $counterparty = $this->itemProvider->provide($operation, $uriVariables, $context);
        if ($counterparty instanceof ContactCounterparty) {
            $this->usage->fill([$counterparty]);
        }

        return $counterparty;
    }
}
