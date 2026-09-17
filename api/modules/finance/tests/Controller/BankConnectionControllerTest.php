<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\BankConnection;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The two ways out of a link that stopped working: send the user back through
 * consent, or drop it. Without them a failed journey is a dead end.
 */
class BankConnectionControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    private function connectionFor(string $fixture = 'test_user'): BankConnection
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $connection = new BankConnection();
        $connection->setUser($this->getFixture($fixture));
        $connection->setBankName('Revolut');
        $em->persist($connection);
        $em->flush();

        return $connection;
    }

    public function testForgettingWithoutAuthenticationIsRefused(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $connection = $this->connectionFor();

        $this->client->request('DELETE', '/api/finance/bank-connections/' . $connection->getId());

        self::assertResponseStatusCodeSame(401);
    }

    public function testAConnectionThatIsNotYoursIsNotFound(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request(
            'DELETE',
            '/api/finance/bank-connections/01JBKQZ0000000000000000000',
            [],
            [],
            $this->authHeaders(),
        );

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnIdThatIsNotAnIdentifierIsNotFound(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('DELETE', '/api/finance/bank-connections/nonsense', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testForgettingRemovesTheLink(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $connection = $this->connectionFor();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request(
            'DELETE',
            '/api/finance/bank-connections/' . $connection->getId(),
            [],
            [],
            $this->authHeaders(),
        );

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(BankConnection::class, $connection->getId()));
    }

    public function testAPendingLinkIsListedAsPendingRatherThanExpired(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->connectionFor();
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/bank-connections', [], [], $this->authHeaders());

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        // A journey never finished has not expired: it was never granted.
        self::assertSame('pending', $data['connections'][0]['status']);
        self::assertFalse($data['connections'][0]['needsReconnecting']);
    }
}
