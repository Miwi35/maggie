<?php

namespace Maggie\Finance\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\CreateProjectCommand;
use Maggie\Finance\Message\DeleteProjectCommand;
use Maggie\Finance\Message\UpdateProjectCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** What a project command changes reaches the screens and the index (MAG-519). */
class ProjectProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function dispatch(object $command): Envelope
    {
        return self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function userId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    public function testCreatingPublishesTheWholeProjectAndIndexesIt(): void
    {
        $project = $this->dispatch(new CreateProjectCommand(
            userId: $this->userId(),
            name: 'Table',
            amountCents: 60000,
            dateMode: 'fixed',
            targetOn: '2027-02-01',
            dependsOn: [$this->id('move')],
        ))->last(HandledStamp::class)?->getResult();
        self::assertInstanceOf(Project::class, $project);

        $topic = '/api/finance/projects/'.$project->getId();
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertSame($topic, $payload['@id']);
        self::assertSame('Table', $payload['name']);
        self::assertSame(3, $payload['position']);
        self::assertSame(['/api/finance/projects/'.$this->id('move')], $payload['dependsOn']);
        // Dated before the move it waits for.
        self::assertSame([$this->id('move')], $payload['unmetDependencies']);
        self::assertSame(2, $payload['wellbeingScore']);
        $this->assertElasticsearchIndexDispatchedFor(Project::class, (string) $project->getId());
    }

    public function testUpdatingPublishesWhatChanged(): void
    {
        $this->dispatch(new UpdateProjectCommand(
            userId: $this->userId(),
            projectId: $this->id('japan'),
            wellbeing: 'essential',
        ));

        $topic = '/api/finance/projects/'.$this->id('japan');
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertSame('essential', $payload['wellbeing']);
        self::assertSame(4, $payload['wellbeingScore']);
        $this->assertElasticsearchIndexDispatchedFor(Project::class, $this->id('japan'));
    }

    public function testDeletingPublishesTheDeletionAndRemovesTheDocument(): void
    {
        $this->dispatch(new DeleteProjectCommand(userId: $this->userId(), projectId: $this->id('sofa')));

        $this->assertMercureDeletePublished('/api/finance/projects/'.$this->id('sofa'));
        $this->assertElasticsearchDeleteDispatched('projects');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        self::assertNull($em->find(Project::class, $this->id('sofa')));
        // The last one gone, nobody moves.
        $this->assertNothingPublishedOn('/api/finance/projects/'.$this->id('japan'));
        $this->assertNothingPublishedOn('/api/finance/projects/'.$this->id('move'));
    }
}
