<?php

namespace Maggie\Finance\Tests\Controller;

use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The callback is the one finance route that must answer without a token: the
 * user arrives from their bank, carrying nothing of ours.
 */
class BankCallbackControllerTest extends WebTestCase
{
    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testItAnswersWithoutAnyAuthentication(): void
    {
        $this->client->request('GET', '/api/finance/bank-callback?state=nope&code=nope');

        // Anything but 401: the user has no token when coming back.
        self::assertNotSame(401, $this->client->getResponse()->getStatusCode());
        self::assertResponseRedirects();
    }

    public function testARefusalAtTheBankIsSentBackAsAnOutcome(): void
    {
        $this->client->request('GET', '/api/finance/bank-callback?error=access_denied');

        self::assertResponseRedirects();
        self::assertStringContainsString('outcome=refused', $this->client->getResponse()->headers->get('Location'));
    }

    public function testAnAnswerWithoutACodeIsReportedAsIncomplete(): void
    {
        $this->client->request('GET', '/api/finance/bank-callback?state=something');

        self::assertStringContainsString('outcome=incomplete', $this->client->getResponse()->headers->get('Location'));
    }

    public function testAnUnknownStateIsRejected(): void
    {
        $this->client->request('GET', '/api/finance/bank-callback?state=never-issued&code=abc');

        self::assertStringContainsString('outcome=unknown', $this->client->getResponse()->headers->get('Location'));
    }
}
