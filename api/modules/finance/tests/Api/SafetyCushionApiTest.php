<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\SafetyCushion;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class SafetyCushionApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
    }

    public function testCushionRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/safety_cushions/me', [], [], [
            'HTTP_ACCEPT' => 'application/ld+json',
        ]);

        self::assertResponseStatusCodeSame(401);
    }

    public function testGetReturnsTheUserCushion(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/safety_cushions/me', [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(3, $data['targetMonths']);
        self::assertSame(250000, $data['monthlyNetIncomeCents']);
    }

    public function testGetCreatesTheCushionOnFirstVisit(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/safety_cushions/me', [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(SafetyCushion::class)->findAll());
    }

    /** @param array<string, mixed> $body */
    private function configure(array $body): void
    {
        $this->client->request('PATCH', '/api/finance/cushion-config', [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testConfigRequiresAuthentication(): void
    {
        $this->client->request('PATCH', '/api/finance/cushion-config', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['targetMonths' => 6], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testConfigUpdatesTheTargetAndPublishes(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->configure(['targetMonths' => 6, 'rechargeCapCents' => 20000]);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(6, $data['targetMonths']);
        self::assertSame(20000, $data['rechargeCapCents']);
        self::assertSame(1500000, $data['targetCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(SafetyCushion::class, $this->getFixture('cushion')->getId());
        self::assertSame(6, $stored->getTargetMonths());

        $this->assertMercureUpdatePublished('/safety_cushions/');
    }

    public function testConfigLeavesUntouchedFieldsAlone(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->configure(['rechargeCapCents' => 20000]);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        // The income and target months were not in the payload: they stand.
        self::assertSame(3, $data['targetMonths']);
        self::assertSame(250000, $data['monthlyNetIncomeCents']);
    }

    public function testConfigRejectsAnImpossibleTarget(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->configure(['targetMonths' => 0]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testConfigRejectsANonIntegerValue(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->configure(['targetMonths' => 'six']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testConfigNeedsSomethingToChange(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->configure(['nickname' => 'coussin']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testStatusEndpointReportsTheRechargePlan(): void
    {
        $this->loadFixtures('safety_cushion.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/cushion-status', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('building', $data['state']);
        self::assertSame(450000, $data['currentCents']);
        self::assertSame(750000, $data['targetCents']);
        self::assertSame(15000, $data['monthlyRechargeCents']);
        self::assertTrue($data['blocksGreenScore']);
    }

    public function testStatusEndpointRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/finance/cushion-status');

        self::assertResponseStatusCodeSame(401);
    }
}
