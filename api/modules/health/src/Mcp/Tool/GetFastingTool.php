<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\FastingReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_fasting', description: 'Read the user\'s intermittent fast of one day (Santé › Nutrition), date YYYY-MM-DD. Nothing is started by hand: the food journal decides. Returns {date, protocol, windowStart, windowEnd, openedAt, lastIntakeAt, state, nightFastMinutes}: protocol is fasting hours:eating window hours (14:10, 16:8, 18:6 or 20:4), null when the user does not fast (change it with update_health_profile); windowStart and windowEnd « HH:MM », the eating window of the profile; openedAt the day\'s first intake, which opens the window; lastIntakeAt the latest intake so far, the fast in progress counts from it; state fasting (nothing eaten yet today), open (window open, until windowEnd) or closed (window over, fasting until tomorrow\'s windowStart); nightFastMinutes the fast that ended that day, from the day before\'s last intake to the day\'s first. A future day gives its window only. Encourage, never reproach: a window eaten past is not a fault.')]
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
