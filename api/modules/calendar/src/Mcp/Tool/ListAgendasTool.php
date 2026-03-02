<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Repository\AgendaRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_agendas', description: 'List all available agendas (calendars). Returns each agenda with its ID, name, color, and whether it is the default.')]
class ListAgendasTool
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(): string
    {
        $agendas = $this->agendaRepository->findAll();

        $result = array_map(fn ($agenda) => [
            'id' => (string) $agenda->getId(),
            'name' => $agenda->getName(),
            'color' => $agenda->getColor(),
            'isDefault' => $agenda->isDefault(),
        ], $agendas);

        return json_encode(['agendas' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
