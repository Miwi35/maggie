<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The project list is served from Elasticsearch: a project rebuilt from its document
 * must still carry its investment, which the document does not hold (MAG-557).
 */
class ProjectInvestmentHydrationTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('project_investment.yaml');
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function hydrate(string $fixture): Project
    {
        $project = $this->em()->find(Project::class, (string) $this->getFixture($fixture)->getId());
        \assert($project instanceof Project);
        $source = json_decode(json_encode($project->toSearchDocument(), \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
        $source['id'] = (string) $project->getId();
        $this->em()->clear();

        $hydrated = (new ElasticsearchEntityHydrator($this->em()))->hydrate($source, Project::class);
        \assert($hydrated instanceof Project);

        return $hydrated;
    }

    public function testAProjectRebuiltFromElasticsearchKeepsItsInvestment(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);

        $this->client->request('PUT', '/api/finance/projects/'.$this->getFixture('box')->getId().'/investment', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'],
            $this->authHeaders(),
        ), json_encode([
            'downPaymentCents' => 400000,
            'loan' => ['annualRateBasisPoints' => 390, 'durationMonths' => 120],
            'monthlyRentCents' => 16000,
        ], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $investment = $this->hydrate('box')->getInvestment();

        self::assertNotNull($investment);
        self::assertSame(16000, $investment->getMonthlyRentCents());
    }

    public function testAPlainProjectRebuiltFromElasticsearchHasNoInvestment(): void
    {
        self::assertNull($this->hydrate('car')->getInvestment());
    }
}
