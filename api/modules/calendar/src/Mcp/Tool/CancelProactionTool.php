<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'cancel_proaction', description: 'Cancel a proaction by its id (from list_proactions): it is removed from the « Maggie » agenda and will not run. This time only — to stop a lasting habit, change the instruction instead.')]
class CancelProactionTool
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $this->proactions->cancel($this->userContext->requireUser(), $id);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true, 'message' => 'Proaction cancelled.'], JSON_THROW_ON_ERROR);
    }
}
