<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

use Symfony\Contracts\HttpClient\HttpClientInterface;

/** Asks the agent to wipe (or, dry-run, count) one user's data in its own database. */
final class AgentResetClient
{
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $agentServiceUrl,
        private readonly string $serviceToken,
    ) {
    }

    /**
     * @return array<string, int> rows deleted (or that would be) per agent table
     *
     * @throws \Symfony\Contracts\HttpClient\Exception\ExceptionInterface on transport errors and non-2xx answers
     */
    public function reset(string $userId, bool $dryRun): array
    {
        $response = $this->httpClient->request('POST', rtrim($this->agentServiceUrl, '/').'/internal/recette/reset', [
            'auth_bearer' => $this->serviceToken,
            'json' => ['userId' => $userId, 'dryRun' => $dryRun],
            'timeout' => 60,
        ]);

        return $response->toArray()['deleted'] ?? [];
    }
}
