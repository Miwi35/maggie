<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Category\StandardCategories;
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
        self::assertContains('Prêts', $data['names']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $housing = $em->getRepository(Category::class)->findOneBy(['name' => 'Logement']);
        self::assertSame(ObligationFlag::Mandatory, $housing->getObligation());
        self::assertNotNull($housing->getColor());

        // Written straight to the database: without indexing, no list shows them.
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testWhatComesInIsClassifiedToo(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->install();

        self::assertContains('Salaire', $data['names']);
        self::assertContains('Aides & allocations', $data['names']);
        self::assertContains('Remboursements', $data['names']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $salary = $em->getRepository(Category::class)->findOneBy(['name' => 'Salaire']);

        // A wage filed as a mandatory expense would be a lie every report reads.
        self::assertSame(ObligationFlag::Income, $salary->getObligation());
    }

    public function testTheSalaryAndTheBonusesCarryTheirStandardKey(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->install();

        self::assertContains('Primes', $data['names']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $repository = $em->getRepository(Category::class);
        self::assertSame(StandardCategories::SALARY, $repository->findOneBy(['name' => 'Salaire'])->getStandardKey());
        $bonus = $repository->findOneBy(['name' => 'Primes']);
        self::assertSame(StandardCategories::BONUS, $bonus->getStandardKey());
        self::assertSame(ObligationFlag::Income, $bonus->getObligation());
        self::assertNull($repository->findOneBy(['name' => 'Logement'])->getStandardKey());
    }

    /** A renamed « Salaire » is still the salary: installing again creates no second one. */
    public function testInstallingAgainAfterARenameCreatesNoSecondSalary(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->install();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $repository = $em->getRepository(Category::class);
        $repository->findOneBy(['name' => 'Salaire'])->setName('Paie');
        $repository->findOneBy(['name' => 'Primes'])->setName('Bonus');
        $em->flush();

        $data = $this->install();

        self::assertResponseIsSuccessful();
        self::assertSame(0, $data['created']);
        $em->clear();
        self::assertNull($repository->findOneBy(['name' => 'Salaire']));
        self::assertCount(1, $repository->findBy(['standardKey' => StandardCategories::SALARY]));
        self::assertSame('Paie', $repository->findOneBy(['standardKey' => StandardCategories::SALARY])->getName());
    }

    public function testAHandMadeSalaryReceivesTheKeyAndKeepsItsName(): void
    {
        $this->loadFixtures('bank_connection.yaml');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $theirs = new Category();
        $theirs->setUser($this->getFixture('test_user'));
        $theirs->setName('SALAIRE');
        $theirs->setObligation(ObligationFlag::Income);
        $em->persist($theirs);
        $em->flush();

        $this->authenticateAsUser($this->getFixture('test_user'));
        $data = $this->install();

        self::assertNotContains('Salaire', $data['names']);

        $em->clear();
        $refreshed = $em->find(Category::class, $theirs->getId());
        self::assertSame('SALAIRE', $refreshed->getName());
        self::assertSame(StandardCategories::SALARY, $refreshed->getStandardKey());
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
