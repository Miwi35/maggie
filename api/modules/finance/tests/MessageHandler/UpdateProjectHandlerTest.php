<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\UpdateProjectCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A PATCH resolves the dependsOn IRIs through the Elasticsearch item provider:
 * unmanaged copies land in the managed collection before the handler runs
 * (MAG-519). The handler must flush with the managed projects instead.
 */
class UpdateProjectHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/project.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    /** What ElasticsearchItemProvider builds: same id, never managed. */
    private function detachedCopy(Project $project): Project
    {
        $copy = (new \ReflectionClass(Project::class))->newInstanceWithoutConstructor();
        foreach (['id' => $project->getId(), 'user' => $project->getUser(), 'name' => $project->getName()] as $field => $value) {
            (new \ReflectionProperty(Project::class, $field))->setValue($copy, $value);
        }

        return $copy;
    }

    public function testANewDependencyResolvedAsADetachedCopyIsFlushedAsTheManagedProject(): void
    {
        $em = $this->em();
        $em->clear();
        $sofa = $em->find(Project::class, $this->id('sofa'));
        $japan = $em->find(Project::class, $this->id('japan'));
        \assert($sofa instanceof Project && $japan instanceof Project);
        $sofa->getDependsOn()->add($this->detachedCopy($japan));

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new UpdateProjectCommand(
            userId: (string) $sofa->getUser()->getId(),
            projectId: $this->id('sofa'),
            dependsOn: [$this->id('move'), $this->id('japan')],
        ));

        $em->clear();
        $reloaded = $em->find(Project::class, $this->id('sofa'));
        \assert($reloaded instanceof Project);
        $ids = $reloaded->dependencyIds();
        sort($ids);
        $expected = [$this->id('move'), $this->id('japan')];
        sort($expected);
        self::assertSame($expected, $ids);
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('sofa'));
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('sofa'));
    }
}
