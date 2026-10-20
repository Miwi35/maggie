<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** POST /api/finance/projects/reorder: the user's whole priority order (MAG-519). */
class ReorderProjectsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const UNCHANGED = ['Voyage au Japon' => 0, 'Déménagement' => 1, 'Canapé' => 2];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function reorder(mixed $body, bool $authenticated = true): void
    {
        $headers = ['CONTENT_TYPE' => 'application/json'];
        if ($authenticated) {
            $user = $this->getFixture('test_user');
            \assert($user instanceof User);
            $this->authenticateAsUser($user);
            $headers = array_merge($headers, $this->authHeaders());
        }

        $this->client->request('POST', '/api/finance/projects/reorder', [], [], $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    /** @return array<string, int> name => position */
    private function positions(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        $positions = [];
        foreach ($em->getRepository(Project::class)->findBy(['user' => $this->getFixture('test_user')->getId()], ['position' => 'ASC']) as $project) {
            $positions[$project->getName()] = $project->getPosition();
        }

        return $positions;
    }

    private function assertRefused(): void
    {
        self::assertResponseStatusCodeSame(400);
        self::assertSame(self::UNCHANGED, $this->positions());
        $this->assertMercureUpdateCount(0);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('japan'), $this->id('move')]], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(self::UNCHANGED, $this->positions());
    }

    public function testMissingIdsReturns400(): void
    {
        $this->reorder([]);

        $this->assertRefused();
    }

    public function testIdsThatAreNotStringsReturn400(): void
    {
        $this->reorder(['ids' => [1, 2, 3]]);
        $this->assertRefused();

        $this->reorder(['ids' => 'all']);
        $this->assertRefused();
    }

    public function testADuplicateReturns400(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('sofa'), $this->id('japan'), $this->id('move')]]);

        $this->assertRefused();
    }

    public function testAnUnknownIdReturns400(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('japan'), '01ARZ3NDEKTSV4RRFFQ69G5FAV']]);

        $this->assertRefused();
    }

    public function testAnotherUsersProjectReturns400(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('japan'), $this->id('move'), $this->id('secret')]]);

        $this->assertRefused();
        self::assertStringNotContainsString('Projet secret', (string) $this->client->getResponse()->getContent());
    }

    public function testAPartialListReturns400(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('japan')]]);

        $this->assertRefused();
        self::assertStringContainsString($this->id('move'), (string) $this->client->getResponse()->getContent());
    }

    public function testReorderRenumbersPublishesAndIndexes(): void
    {
        $this->reorder(['ids' => [$this->id('sofa'), $this->id('japan'), $this->id('move')]]);

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(['Canapé', 'Voyage au Japon', 'Déménagement'], array_column($data['projects'], 'name'));
        self::assertSame([0, 1, 2], array_column($data['projects'], 'position'));
        self::assertSame($this->id('sofa'), $data['projects'][0]['id']);
        self::assertSame([$this->id('move')], $data['projects'][0]['dependsOn']);
        self::assertArrayHasKey('unmetDependencies', $data['projects'][0]);

        self::assertSame(['Canapé' => 0, 'Voyage au Japon' => 1, 'Déménagement' => 2], $this->positions());

        foreach (['sofa' => 0, 'japan' => 1, 'move' => 2] as $fixture => $position) {
            $topic = '/api/finance/projects/'.$this->id($fixture);
            $this->assertMercurePublishedOnce($topic);
            self::assertSame($position, $this->mercurePayloadsOn($topic)[0]['position']);
            $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id($fixture));
        }
    }

    public function testAProjectThatKeepsItsPlaceIsNotRepublished(): void
    {
        $this->reorder(['ids' => [$this->id('japan'), $this->id('sofa'), $this->id('move')]]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Voyage au Japon' => 0, 'Canapé' => 1, 'Déménagement' => 2], $this->positions());
        $this->assertNothingPublishedOn('/api/finance/projects/'.$this->id('japan'));
        $this->assertMercurePublishedOnce('/api/finance/projects/'.$this->id('sofa'));
        $this->assertMercurePublishedOnce('/api/finance/projects/'.$this->id('move'));
    }
}
