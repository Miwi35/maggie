<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Enum\Activity;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\ActivityStatusRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_activity_status', description: 'Read where the user stands in an activity left in progress, to resume at the right place. activity is cuisine, comptes or bilan (the evening review: step, answers, instructions). Returns the status {activity, origin, state, startedAt, updatedAt}, or null when nothing is in progress. A pure read.')]
class GetActivityStatusTool
{
    public function __construct(
        private readonly ActivityStatusRepository $repository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $activity): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $known = Activity::tryFrom($activity);
        if (null === $known) {
            return json_encode(['error' => \sprintf('activity must be one of %s.', implode(', ', Activity::values()))], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->repository->findOneByUserAndActivity($user, $known)?->describe(), JSON_THROW_ON_ERROR);
    }
}
