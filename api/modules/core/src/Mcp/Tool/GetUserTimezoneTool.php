<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserPreferenceRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_user_timezone', description: "Read the IANA timezone (e.g. Europe/Paris) the user chose in their preferences. Use it to reason about the user's local time. Also gives their working hours (HH:MM start and end, ISO days 1 = Monday … 7) and sickSince: the day they fell sick, YYYY-MM-DD, null when they are not sick (set_sick).")]
class GetUserTimezoneTool
{
    public const DEFAULT_TIMEZONE = 'Europe/Paris';

    public function __construct(
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        // No preference row yet: the defaults a new one would carry.
        $preference = $this->preferenceRepository->findOneByUser($user) ?? new UserPreference();

        // The working hours ride along (MAG-790): the agent reads the timezone at every turn,
        // and needs both to tell whether the user is at work.
        return json_encode([
            'timezone' => $preference->getTimezone(),
            'email' => $user->getEmail(),
            'workStartTime' => $preference->getWorkStartTime(),
            'workEndTime' => $preference->getWorkEndTime(),
            'workDays' => $preference->getWorkDays(),
            // The sick state rides along too (MAG-810): no extra call to know it.
            'sickSince' => $preference->getSickSince()?->format('Y-m-d'),
        ], JSON_THROW_ON_ERROR);
    }
}
