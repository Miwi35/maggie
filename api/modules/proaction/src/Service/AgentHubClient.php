<?php

declare(strict_types=1);

namespace Maggie\Proaction\Service;

use Symfony\Contracts\HttpClient\HttpClientInterface;

class AgentHubClient
{
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $agentHubUrl,
    ) {
    }

    /**
     * @return array{response: string, tool_calls: list<array<string, mixed>>}
     */
    public function executeProaction(string $userId, string $prompt): array
    {
        $response = $this->httpClient->request('POST', $this->agentHubUrl . '/proaction', [
            'json' => [
                'user_id' => $userId,
                'prompt' => $prompt,
            ],
        ]);

        return $response->toArray();
    }
}
