<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserPreferenceRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_user_timezone', description: "Read the IANA timezone (e.g. Europe/Paris) the user chose in their preferences. Use it to reason about the user's local time.")]
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

        $preference = $this->preferenceRepository->findOneByUser($user);

        return json_encode([
            'timezone' => $preference?->getTimezone() ?? self::DEFAULT_TIMEZONE,
        ], JSON_THROW_ON_ERROR);
    }
}
