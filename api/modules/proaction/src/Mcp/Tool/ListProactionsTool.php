<?php

declare(strict_types=1);

namespace Maggie\Proaction\Mcp\Tool;

use Maggie\Proaction\Entity\ProactionStatus;
use Maggie\Proaction\Repository\ProactionRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_proactions', description: 'List proactions. Optionally filter by status (pending, running, success, failed). Returns the 50 most recent proactions.')]
class ListProactionsTool
{
    public function __construct(
        private readonly ProactionRepository $proactionRepository,
    ) {
    }

    public function __invoke(?string $status = null): string
    {
        if ($status !== null) {
            $statusEnum = ProactionStatus::tryFrom($status);
            if ($statusEnum === null) {
                return json_encode(['error' => 'Invalid status. Valid: pending, running, success, failed'], JSON_THROW_ON_ERROR);
            }
            $proactions = $this->proactionRepository->findByStatus($statusEnum);
        } else {
            $proactions = $this->proactionRepository->findRecent();
        }

        $result = array_map(fn ($p) => [
            'id' => (string) $p->getId(),
            'scheduledAt' => $p->getScheduledAt()->format('c'),
            'prompt' => $p->getPrompt(),
            'status' => $p->getStatus()->value,
            'response' => $p->getResponse(),
            'error' => $p->getError(),
            'createdAt' => $p->getCreatedAt()->format('c'),
            'completedAt' => $p->getCompletedAt()?->format('c'),
        ], $proactions);

        return json_encode(['proactions' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
