<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ProjectDateMode;
use Maggie\Finance\Enum\ProjectWellbeing;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The projects through the API Platform resource (MAG-519). */
class ProjectApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('project.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed>|null $body */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function iri(string $fixture): string
    {
        return '/api/finance/projects/'.$this->id($fixture);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): Project
    {
        $project = $this->em()->find(Project::class, $this->id($fixture));
        \assert($project instanceof Project);

        return $project;
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Project::class)->count([]);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCollectionRequiresAuthentication(): void
    {
        $this->send('GET', '/api/finance/projects', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateGoesLastPersistsPublishesAndIndexes(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', [
            'name' => 'Table',
            'amountCents' => 60000,
            'wellbeing' => 'high',
            'dateMode' => 'deadline',
            'targetOn' => '2027-06-30',
            'dependsOn' => [$this->iri('move')],
            // Read-only: a client cannot choose its place.
            'position' => 0,
        ]);

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('Table', $data['name']);
        self::assertSame(3, $data['position']);
        self::assertSame('2027-06-30', $data['targetOn']);
        self::assertSame([$this->iri('move')], $data['dependsOn']);
        self::assertSame(3, $data['wellbeingScore']);
        self::assertSame([], $data['unmetDependencies']);
        self::assertSame('EUR', $data['currency']);

        $project = $this->em()->getRepository(Project::class)->findOneBy(['name' => 'Table']);
        self::assertInstanceOf(Project::class, $project);
        self::assertSame(3, $project->getPosition());
        self::assertSame(60000, $project->getAmountCents());
        self::assertSame(ProjectWellbeing::High, $project->getWellbeing());
        self::assertSame(ProjectDateMode::Deadline, $project->getDateMode());
        self::assertSame('2027-06-30', $project->getTargetOn()?->format('Y-m-d'));
        self::assertSame([$this->id('move')], $project->dependencyIds());
        self::assertSame((string) $this->getFixture('test_user')->getId(), (string) $project->getUser()->getId());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$project->getId());
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testCreateWithDefaults(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000]);

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('medium', $data['wellbeing']);
        self::assertSame(2, $data['wellbeingScore']);
        self::assertSame('none', $data['dateMode']);
        self::assertArrayHasKey('targetOn', $data);
        self::assertNull($data['targetOn']);
        self::assertArrayHasKey('note', $data);
        self::assertSame([], $data['dependsOn']);
    }

    public function testCreateWithoutNameIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['amountCents' => 80000]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateWithoutAPositiveAmountIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo']);
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 0]);
        self::assertResponseStatusCodeSame(422);

        self::assertSame(4, $this->rows());
    }

    public function testAFixedOrDeadlineProjectNeedsADate(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Mariage', 'amountCents' => 900000, 'dateMode' => 'fixed']);
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/finance/projects', ['name' => 'Mariage', 'amountCents' => 900000, 'dateMode' => 'deadline']);
        self::assertResponseStatusCodeSame(422);

        self::assertSame(4, $this->rows());
    }

    public function testAProjectWithoutDateModeRefusesADate(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000, 'targetOn' => '2027-05-01']);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(4, $this->rows());
    }

    public function testADayThatDoesNotExistIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000, 'dateMode' => 'fixed', 'targetOn' => '2027-13-45']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(4, $this->rows());
    }

    public function testAnUnknownWellbeingIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000, 'wellbeing' => 'vital']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(4, $this->rows());
    }

    public function testAProjectCannotDependOnItself(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('move'), ['dependsOn' => [$this->iri('move')]]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame([], $this->reload('move')->dependencyIds());
        $this->assertMercureUpdateCount(0);
    }

    public function testADependencyCycleIsRefused(): void
    {
        $this->login();

        // The sofa waits for the move: the move cannot wait for the sofa.
        $this->send('PATCH', $this->iri('move'), ['dependsOn' => [$this->iri('sofa')]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('cycle', (string) $this->client->getResponse()->getContent());
        self::assertSame([], $this->reload('move')->dependencyIds());
        $this->assertMercureUpdateCount(0);
    }

    public function testALongerDependencyCycleIsRefused(): void
    {
        $this->login();

        // Japan → sofa → move: fine.
        $this->send('PATCH', $this->iri('japan'), ['dependsOn' => [$this->iri('sofa')]]);
        self::assertResponseIsSuccessful();

        // Move → Japan would close move → Japan → sofa → move.
        $this->send('PATCH', $this->iri('move'), ['dependsOn' => [$this->iri('japan')]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame([], $this->reload('move')->dependencyIds());
    }

    public function testADependencyOnAnotherUsersProjectIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/projects', ['name' => 'Vélo', 'amountCents' => 80000, 'dependsOn' => [$this->iri('secret')]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringNotContainsString('Projet secret', (string) $this->client->getResponse()->getContent());
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheCollectionIsTheUsersInPriorityOrder(): void
    {
        $this->login();

        $this->send('GET', '/api/finance/projects');

        self::assertResponseIsSuccessful();
        $members = $this->response()['member'];
        self::assertSame(['Voyage au Japon', 'Déménagement', 'Canapé'], array_column($members, 'name'));
        self::assertSame([0, 1, 2], array_column($members, 'position'));

        $sofa = $members[2];
        self::assertSame($this->iri('sofa'), $sofa['@id']);
        self::assertSame($this->id('sofa'), $sofa['id']);
        self::assertSame([$this->iri('move')], $sofa['dependsOn']);
        self::assertSame('none', $sofa['dateMode']);
        self::assertNull($sofa['targetOn']);
        self::assertSame(1, $sofa['wellbeingScore']);
        self::assertSame('Le gris, chez Maisons du Monde', $sofa['note']);
        // Undated, the sofa cannot be dated before the move.
        self::assertSame([], $sofa['unmetDependencies']);

        self::assertSame('2027-08-31', $members[0]['targetOn']);
        self::assertSame('deadline', $members[0]['dateMode']);
    }

    /** « Canapé » waits for « Déménagement » on 2027-03-01: dated 2027-01-15, it is flagged. */
    public function testASofaDatedBeforeTheMoveItWaitsForIsFlagged(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('sofa'), ['dateMode' => 'fixed', 'targetOn' => '2027-01-15']);

        self::assertResponseIsSuccessful();
        self::assertSame([$this->id('move')], $this->response()['unmetDependencies']);

        $sofa = $this->reload('sofa');
        self::assertSame(ProjectDateMode::Fixed, $sofa->getDateMode());
        self::assertSame('2027-01-15', $sofa->getTargetOn()?->format('Y-m-d'));

        $payloads = $this->mercurePayloadsOn('/api/finance/projects/'.$this->id('sofa'));
        self::assertCount(1, $payloads);
        self::assertSame([$this->id('move')], $payloads[0]['unmetDependencies']);
        self::assertSame('2027-01-15', $payloads[0]['targetOn']);
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('sofa'));

        $this->send('GET', $this->iri('sofa'));
        self::assertSame([$this->id('move')], $this->response()['unmetDependencies']);

        $this->send('GET', '/api/finance/projects');
        self::assertSame([$this->id('move')], $this->response()['member'][2]['unmetDependencies']);
    }

    public function testMovingTheMoveRepublishesTheSofaThatWaitsForIt(): void
    {
        $this->login();
        $this->send('PATCH', $this->iri('sofa'), ['dateMode' => 'fixed', 'targetOn' => '2027-01-15']);
        $this->resetMercure();

        $this->send('PATCH', $this->iri('move'), ['targetOn' => '2026-12-01']);

        self::assertResponseIsSuccessful();
        $payloads = $this->mercurePayloadsOn('/api/finance/projects/'.$this->id('sofa'));
        self::assertCount(1, $payloads);
        self::assertSame([], $payloads[0]['unmetDependencies']);
    }

    public function testAnotherUsersProjectIsNotFound(): void
    {
        $this->login();

        $this->send('GET', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $this->iri('secret'), ['name' => 'Volé']);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        self::assertSame('Projet secret', $this->reload('secret')->getName());
    }

    public function testSwitchingADatedProjectToNoneNeedsTheDateCleared(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('move'), ['dateMode' => 'none']);
        self::assertResponseStatusCodeSame(422);
        self::assertSame(ProjectDateMode::Fixed, $this->reload('move')->getDateMode());

        $this->send('PATCH', $this->iri('move'), ['dateMode' => 'none', 'targetOn' => null]);
        self::assertResponseIsSuccessful();

        $move = $this->reload('move');
        self::assertSame(ProjectDateMode::None, $move->getDateMode());
        self::assertNull($move->getTargetOn());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('move'));
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('sofa'), ['name' => 'Canapé d’angle'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** The admin sends the whole record read back, `id` an IRI, read-only fields included (MAG-480). */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $this->login();

        $this->send('GET', $this->iri('sofa'));
        self::assertResponseIsSuccessful();
        $body = $this->response();
        $body['id'] = $body['@id'];
        $body['name'] = 'Canapé d’angle';
        $body['position'] = 0;

        $this->send('PATCH', $this->iri('sofa'), $body);

        self::assertResponseIsSuccessful();
        $sofa = $this->reload('sofa');
        self::assertSame('Canapé d’angle', $sofa->getName());
        self::assertSame(2, $sofa->getPosition());
        self::assertSame([$this->id('move')], $sofa->dependencyIds());
        self::assertSame('Le gris, chez Maisons du Monde', $sofa->getNote());
        $this->assertMercureUpdatePublished('/api/finance/projects/');
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $this->send('DELETE', $this->iri('sofa'), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testDeleteLeavesTheDependentsAndCompactsThePositions(): void
    {
        $this->login();

        $this->send('DELETE', $this->iri('move'));

        self::assertResponseStatusCodeSame(204);
        $em = $this->em();
        self::assertNull($em->find(Project::class, $this->id('move')));
        self::assertSame(0, $this->reload('japan')->getPosition());
        $sofa = $this->reload('sofa');
        self::assertSame(1, $sofa->getPosition());
        self::assertSame([], $sofa->dependencyIds());

        $this->assertMercureDeletePublished('/api/finance/projects/'.$this->id('move'));
        $sofaPayloads = $this->mercurePayloadsOn('/api/finance/projects/'.$this->id('sofa'));
        self::assertCount(1, $sofaPayloads);
        self::assertSame([], $sofaPayloads[0]['dependsOn']);
        self::assertSame(1, $sofaPayloads[0]['position']);
        $this->assertNothingPublishedOn('/api/finance/projects/'.$this->id('japan'));
        $this->assertElasticsearchDeleteDispatched('projects');
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('sofa'));
    }
}
