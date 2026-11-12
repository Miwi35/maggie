<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateTaskCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class CreateTaskHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('CreateTaskHandlerTest.yaml');
    }

    private function create(string $title): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateTaskCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            title: $title,
        ));
    }

    /** @return list<Agenda> */
    private function tasksAgendas(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->findBy(['module' => Agenda::MODULE_TASKS]);
    }

    public function testFirstTaskCreatesTheTasksModuleAgenda(): void
    {
        $this->create('First');

        $agendas = $this->tasksAgendas();
        self::assertCount(1, $agendas);
        self::assertSame('Tâches', $agendas[0]->getName());
        self::assertSame('#E8A9D9', $agendas[0]->getColor());
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(Task::class)->findAll());
    }

    public function testSecondTaskReusesIt(): void
    {
        $this->create('First');
        $this->create('Second');

        self::assertCount(1, $this->tasksAgendas());
    }

    public function testOrdinaryAgendaNamedTachesIsNotAdopted(): void
    {
        $this->create('First');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $ordinary = $em->getRepository(Agenda::class)->find($this->getFixture('ordinary_taches')->getId());
        self::assertNull($ordinary->getModule());
        self::assertCount(1, $this->tasksAgendas());
    }
}
