<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\FastingReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_fasting', description: 'Reads one day\'s intermittent fast, date YYYY-MM-DD, derived from the food journal. Returns {date, protocol (14:10, 16:8, 18:6, 20:4; null if none), windowStart, windowEnd (HH:MM), openedAt (first intake), lastIntakeAt, state (fasting: nothing eaten yet; open: until windowEnd; closed), nightFastMinutes (previous day\'s last intake to this day\'s first)}. A future day gives its window only.')]
final class GetFastingTool
{
    public function __construct(
        private readonly FastingReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            return json_encode($this->reader->day($this->userContext->requireUser(), $date), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
