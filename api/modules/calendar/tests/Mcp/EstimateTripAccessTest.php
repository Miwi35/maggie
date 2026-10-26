<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** estimate_trip is reached only with a bearer, like every MCP tool (MAG-653). */
class EstimateTripAccessTest extends WebTestCase
{
    public function testWithoutABearerTheCallIsRefusedWith401(): void
    {
        $client = self::createClient();

        $client->request('POST', '/_mcp', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json, text/event-stream',
        ], content: json_encode([
            'jsonrpc' => '2.0',
            'id' => 1,
            'method' => 'tools/call',
            'params' => ['name' => 'estimate_trip', 'arguments' => ['eventId' => '01J0000000000000000000000A', 'modes' => ['drive']]],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
