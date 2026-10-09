<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;

/**
 * The user's agendas, without the ones a module keeps for itself (MAG-354).
 *
 * « Repas » is a module's agenda, shown by the module's own view and by one
 * line of the general calendar: listed here as well, it showed twice. A client
 * that wants one asks for it by name — `?module=cookbook`.
 *
 * The exclusion is a filter added to the request before either provider reads it
 * (`exists[module]=false`), so Elasticsearch and the Doctrine fallback agree.
 *
 * @implements ProviderInterface<object>
 */
final class AgendaCollectionProvider implements ProviderInterface
{
    public function __construct(private readonly ElasticsearchCollectionProvider $inner)
    {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $filters = $context['filters'] ?? [];

        $module = $filters['module'] ?? null;
        $named = (\is_string($module) && '' !== $module) || (\is_array($module) && [] !== $module);

        if (!$named && !isset($filters['exists']['module'])) {
            $filters['exists'] = array_merge(\is_array($filters['exists'] ?? null) ? $filters['exists'] : [], ['module' => 'false']);
            $context['filters'] = $filters;
        }

        return $this->inner->provide($operation, $uriVariables, $context);
    }
}
