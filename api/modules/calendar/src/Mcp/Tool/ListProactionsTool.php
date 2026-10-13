<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_proactions', description: 'List the proactions Maggie scheduled for the user, by time: each with its prompt, scheduledAt and status (pending, running, completed with its response, failed with its error). Optional from and to (ISO 8601, or YYYY-MM-DD for a whole day, read in Europe/Paris) bound scheduledAt; optional status filters. Use it before scheduling, so as not to schedule one that already exists.')]
class ListProactionsTool
{
    public function __construct(
        private readonly ProactionRepository $repository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $from = null, ?string $to = null, ?string $status = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $proactions = $this->repository->findForUser(
                $user,
                self::bound($from, 'from', false),
                self::bound($to, 'to', true),
                ProactionStatus::fromFilter($status),
            );
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode([
            'proactions' => array_map(static fn (Proaction $p) => Proactions::describe($p), $proactions),
            'count' => count($proactions),
        ], JSON_THROW_ON_ERROR);
    }

    /** A bare day as `to` reaches the end of that day: « to 2037-01-15 » includes the 15th. */
    private static function bound(?string $value, string $name, bool $isEnd): ?\DateTimeImmutable
    {
        if (null === $value || '' === trim($value)) {
            return null;
        }

        try {
            $read = new \DateTimeImmutable(trim($value), new \DateTimeZone(Proaction::FALLBACK_TIME_ZONE));
        } catch (\Exception $e) {
            throw new \DomainException("{$name} is not a date: \"{$value}\". Use ISO 8601 or YYYY-MM-DD.", 0, $e);
        }

        // `2037-02-30` would roll over to March: what was named has to be what was read.
        if (1 === preg_match('/^\d{4}-\d{2}-\d{2}/', trim($value)) && $read->format('Y-m-d') !== substr(trim($value), 0, 10)) {
            throw new \DomainException("{$name} is not a date: \"{$value}\".");
        }

        return $isEnd && 1 === preg_match('/^\d{4}-\d{2}-\d{2}$/', trim($value)) ? $read->modify('+1 day -1 second') : $read;
    }
}
