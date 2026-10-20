<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ProjectDateMode;
use Maggie\Finance\Mcp\Tool\ManageProjectsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_projects (MAG-519): every action, and partial requests refused with `current`. */
class ProjectToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageProjectsTool::class);
        \assert($tool instanceof ManageProjectsTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
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

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('projects', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('archive');

        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testListGivesTheUsersProjectsInPriorityOrder(): void
    {
        $this->loginFixtureUser();
        $this->em();

        $data = $this->call('list');

        self::assertSame(['Voyage au Japon', 'Déménagement', 'Canapé'], array_column($data['projects'], 'name'));
        $sofa = $data['projects'][2];
        self::assertSame($this->id('sofa'), $sofa['id']);
        self::assertSame([$this->id('move')], $sofa['dependsOn']);
        self::assertSame('none', $sofa['dateMode']);
        self::assertNull($sofa['targetOn']);
        self::assertSame('comfort', $sofa['wellbeing']);
        self::assertSame(1, $sofa['wellbeingScore']);
        self::assertSame(2, $sofa['position']);
        self::assertSame([], $sofa['unmetDependencies']);
        self::assertSame('2027-08-31', $data['projects'][0]['targetOn']);
    }

    public function testCreatePersistsLastPublishesAndIndexes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Mariage', amountCents: 900000, wellbeing: 'essential', dateMode: 'fixed', targetOn: '2027-06-12', dependsOn: [$this->id('move')], note: 'Salle à réserver');

        self::assertTrue($data['success']);
        self::assertSame(3, $data['project']['position']);
        self::assertSame(4, $data['project']['wellbeingScore']);
        self::assertSame([$this->id('move')], $data['project']['dependsOn']);

        $project = $this->em()->find(Project::class, $data['project']['id']);
        self::assertInstanceOf(Project::class, $project);
        self::assertSame('Mariage', $project->getName());
        self::assertSame(900000, $project->getAmountCents());
        self::assertSame(ProjectDateMode::Fixed, $project->getDateMode());
        self::assertSame('2027-06-12', $project->getTargetOn()?->format('Y-m-d'));
        self::assertSame('Salle à réserver', $project->getNote());
        self::assertSame(3, $project->getPosition());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$project->getId());
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testCreateWithoutAnAmountIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Vélo', wellbeing: 'high');

        self::assertStringContainsString('amountCents', $data['error']);
        self::assertSame(['name' => 'Vélo', 'wellbeing' => 'high'], $data['current']);
        self::assertSame(4, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateWithoutANameIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', amountCents: 80000);

        self::assertStringContainsString('name', $data['error']);
        self::assertSame(['amountCents' => 80000], $data['current']);
        self::assertSame(4, $this->rows());
    }

    public function testADeadlineWithoutADateIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Mariage', amountCents: 900000, dateMode: 'deadline');

        self::assertStringContainsString('targetOn', $data['error']);
        self::assertSame(['name' => 'Mariage', 'amountCents' => 900000, 'dateMode' => 'deadline'], $data['current']);
        self::assertSame(4, $this->rows());
    }

    public function testADateWithoutADateModeIsNotDeduced(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Mariage', amountCents: 900000, targetOn: '2027-06-12');

        self::assertStringContainsString('dateMode', $data['error']);
        self::assertSame('2027-06-12', $data['current']['targetOn']);
        self::assertSame(4, $this->rows());
    }

    public function testADependencyOnAnotherUsersProjectIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Vélo', amountCents: 80000, dependsOn: [$this->id('secret')]);

        self::assertStringContainsString('not found', $data['error']);
        self::assertSame(4, $this->rows());
    }

    public function testUpdateChangesOnlyWhatIsGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', projectId: $this->id('sofa'), amountCents: 150000, dateMode: 'fixed', targetOn: '2027-01-15');

        self::assertTrue($data['success']);
        // Dated before the move it waits for.
        self::assertSame([$this->id('move')], $data['project']['unmetDependencies']);

        $sofa = $this->reload('sofa');
        self::assertSame(150000, $sofa->getAmountCents());
        self::assertSame('Canapé', $sofa->getName());
        self::assertSame('Le gris, chez Maisons du Monde', $sofa->getNote());
        self::assertSame([$this->id('move')], $sofa->dependencyIds());
        self::assertSame('2027-01-15', $sofa->getTargetOn()?->format('Y-m-d'));

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('sofa'));
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('sofa'));
    }

    public function testUpdateReplacesAndClearsTheDependencies(): void
    {
        $this->loginFixtureUser();

        $this->call('update', projectId: $this->id('sofa'), dependsOn: [$this->id('japan'), $this->id('move')]);
        self::assertEqualsCanonicalizing([$this->id('japan'), $this->id('move')], $this->reload('sofa')->dependencyIds());

        $this->call('update', projectId: $this->id('sofa'), clear: ['dependsOn', 'note']);
        $sofa = $this->reload('sofa');
        self::assertSame([], $sofa->dependencyIds());
        self::assertNull($sofa->getNote());
    }

    public function testADateModeWithoutADateIsRefusedWithTheProjectAsItIs(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', projectId: $this->id('sofa'), dateMode: 'deadline');

        self::assertStringContainsString('targetOn', $data['error']);
        self::assertSame($this->id('sofa'), $data['current']['id']);
        self::assertSame('none', $data['current']['dateMode']);
        self::assertSame(ProjectDateMode::None, $this->reload('sofa')->getDateMode());
        $this->assertMercureUpdateCount(0);
    }

    public function testSwitchingToNoneNeedsTheDateClearedExplicitly(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', projectId: $this->id('move'), dateMode: 'none');

        self::assertArrayHasKey('error', $data);
        self::assertSame('fixed', $data['current']['dateMode']);
        self::assertSame('2027-03-01', $data['current']['targetOn']);
        self::assertSame(ProjectDateMode::Fixed, $this->reload('move')->getDateMode());

        $data = $this->call('update', projectId: $this->id('move'), dateMode: 'none', clear: ['targetOn']);

        self::assertTrue($data['success']);
        $move = $this->reload('move');
        self::assertSame(ProjectDateMode::None, $move->getDateMode());
        self::assertNull($move->getTargetOn());
    }

    public function testACycleIsRefusedWithTheProjectAsItIs(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', projectId: $this->id('move'), dependsOn: [$this->id('sofa')]);

        self::assertStringContainsString('cycle', $data['error']);
        self::assertSame([], $data['current']['dependsOn']);
        self::assertSame([], $this->reload('move')->dependencyIds());
    }

    public function testASelfDependencyIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', projectId: $this->id('move'), dependsOn: [$this->id('move')]);

        self::assertStringContainsString('itself', $data['error']);
        self::assertArrayHasKey('current', $data);
    }

    public function testUpdateNeedsAProjectOfTheUser(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('projectId', $this->call('update', name: 'X')['error']);
        self::assertStringContainsString('not found', $this->call('update', projectId: $this->id('secret'), name: 'Volé')['error']);
        self::assertSame('Projet secret', $this->reload('secret')->getName());
    }

    public function testDeleteCompactsThePositions(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', projectId: $this->id('japan'));

        self::assertTrue($data['success']);
        self::assertNull($this->em()->find(Project::class, $this->id('japan')));
        self::assertSame(0, $this->reload('move')->getPosition());
        self::assertSame(1, $this->reload('sofa')->getPosition());
        $this->assertMercureDeletePublished('/api/finance/projects/'.$this->id('japan'));
        $this->assertElasticsearchDeleteDispatched('projects');
    }

    public function testDeleteNeedsAProjectOfTheUser(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('projectId', $this->call('delete')['error']);
        self::assertStringContainsString('not found', $this->call('delete', projectId: $this->id('secret'))['error']);
        self::assertSame(4, $this->rows());
    }

    public function testReorderTakesTheWholeList(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('reorder', ids: [$this->id('move'), $this->id('sofa'), $this->id('japan')]);

        self::assertTrue($data['success']);
        self::assertSame(['Déménagement', 'Canapé', 'Voyage au Japon'], array_column($data['projects'], 'name'));
        self::assertSame(0, $this->reload('move')->getPosition());
        self::assertSame(2, $this->reload('japan')->getPosition());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('japan'));
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('japan'));
    }

    public function testAPartialReorderIsRefusedWithTheCurrentOrder(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('reorder', ids: [$this->id('sofa')]);

        self::assertStringContainsString('every project', $data['error']);
        self::assertSame([
            ['id' => $this->id('japan'), 'name' => 'Voyage au Japon', 'position' => 0],
            ['id' => $this->id('move'), 'name' => 'Déménagement', 'position' => 1],
            ['id' => $this->id('sofa'), 'name' => 'Canapé', 'position' => 2],
        ], $data['current']);
        self::assertSame(2, $this->reload('sofa')->getPosition());
        $this->assertMercureUpdateCount(0);
    }

    public function testAReorderWithoutIdsIsRefusedWithTheCurrentOrder(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('reorder');

        self::assertStringContainsString('ids', $data['error']);
        self::assertCount(3, $data['current']);
    }
}
