<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The starting set of categories: what it lays down, and what it refuses to
 * touch on a second run.
 */
class StandardCategoriesControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetAsyncTransport();
    }

    private function install(): array
    {
        $this->client->request('POST', '/api/finance/categories/standard', [], [], $this->authHeaders());

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/categories/standard');

        self::assertResponseStatusCodeSame(401);
    }

    public function testItLaysDownTheHeadingsABudgetStartsFrom(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->install();

        self::assertResponseIsSuccessful();
        self::assertContains('Logement', $data['names']);
        self::assertContains('Nourriture', $data['names']);
        self::assertContains('Loisirs', $data['names']);
        self::assertContains('Placements', $data['names']);
        self::assertContains('Essence', $data['names']);
        self::assertContains('Imprévus', $data['names']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $housing = $em->getRepository(Category::class)->findOneBy(['name' => 'Logement']);
        self::assertSame(ObligationFlag::Mandatory, $housing->getObligation());
        self::assertNotNull($housing->getColor());

        // Written straight to the database: without indexing, no list shows them.
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testSubscriptionsCarryTheUtilitiesUnderThem(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->install();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $water = $em->getRepository(Category::class)->findOneBy(['name' => 'Eau']);

        // A single "subscriptions" total says nothing: water is not electricity.
        self::assertNotNull($water);
        self::assertSame('Abonnements', $water->getParent()->getName());
    }

    public function testRunningItAgainCreatesNothing(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $first = $this->install();
        $second = $this->install();

        self::assertGreaterThan(0, $first['created']);
        self::assertSame(0, $second['created']);
        self::assertSame($first['created'], $second['kept']);
    }

    public function testAHeadingTheUserAlreadyShapedIsLeftAlone(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Same heading, their own spelling and their own decision about it.
        $theirs = new Category();
        $theirs->setUser($this->getFixture('test_user'));
        $theirs->setName('loisirs');
        $theirs->setObligation(ObligationFlag::Saving);
        $em->persist($theirs);
        $em->flush();

        $this->authenticateAsUser($this->getFixture('test_user'));
        $data = $this->install();

        self::assertNotContains('Loisirs', $data['names']);

        $em->clear();
        $refreshed = $em->find(Category::class, $theirs->getId());
        self::assertSame('loisirs', $refreshed->getName());
        self::assertSame(ObligationFlag::Saving, $refreshed->getObligation());
    }
}
