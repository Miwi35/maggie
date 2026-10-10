<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Finance\Enum\TransferKind;

/**
 * The user's transactions, without the rejected payments (MAG-375).
 *
 * A rejected debit and the credit that gives it back are not operations of the
 * account: the account page lists them apart, one line per rejection. A client
 * that wants them asks by name — `?transferKind=rejected`.
 *
 * The exclusion is a filter added to the request before either provider reads
 * it (`transferKind[]=none&transferKind[]=internal`), so Elasticsearch and the
 * Doctrine fallback agree. It lists the kinds that stay rather than the one
 * that goes, so a kind added to the enum is shown until someone decides otherwise.
 *
 * @implements ProviderInterface<object>
 */
final class TransactionCollectionProvider implements ProviderInterface
{
    public function __construct(private readonly ElasticsearchCollectionProvider $inner)
    {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $filters = $context['filters'] ?? [];

        $named = $filters['transferKind'] ?? null;
        if (!(\is_string($named) && '' !== $named) && !(\is_array($named) && [] !== $named)) {
            $kept = [];
            foreach (TransferKind::cases() as $kind) {
                if (TransferKind::Rejected !== $kind) {
                    $kept[] = $kind->value;
                }
            }
            $filters['transferKind'] = $kept;
            $context['filters'] = $filters;
        }

        return $this->inner->provide($operation, $uriVariables, $context);
    }
}
