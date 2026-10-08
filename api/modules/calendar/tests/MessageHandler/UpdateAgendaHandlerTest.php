<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateAgendaHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateAgendaHandlerTest.yaml');
    }

    private function dispatch(UpdateAgendaCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): Agenda
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->find($this->getFixture('test_agenda')->getId());
    }

    public function testClearingDescriptionAndColor(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('test_agenda')->getId(),
            clearFields: ['description', 'color'],
        ));

        $agenda = $this->reload();
        self::assertNull($agenda->getDescription());
        self::assertNull($agenda->getColor());
        self::assertSame('Test Agenda', $agenda->getName());
        self::assertTrue($agenda->isDefault());
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('test_agenda')->getId(),
            name: 'Renamed',
        ));

        $agenda = $this->reload();
        self::assertSame('Renamed', $agenda->getName());
        self::assertSame('Agenda notes', $agenda->getDescription());
        self::assertSame('#e91e63', $agenda->getColor());
    }

    public function testUnknownAgendaFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Agenda not found');

        $this->dispatch(new UpdateAgendaCommand(agendaId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['color']));
    }

    public function testAnAgendaAModuleKeepsForItselfCanBeRenamed(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('module_agenda')->getId(),
            name: 'Mes repas',
        ));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $agenda = $em->getRepository(Agenda::class)->find($this->getFixture('module_agenda')->getId());
        self::assertSame('Mes repas', $agenda->getName());
        self::assertSame('cookbook', $agenda->getModule());
    }

    public function testAnAgendaAModuleKeepsForItselfCannotBecomeTheDefaultOne(): void
    {
        try {
            $this->dispatch(new UpdateAgendaCommand(
                agendaId: (string) $this->getFixture('module_agenda')->getId(),
                isDefault: true,
            ));
            self::fail('Expected the update to be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(\DomainException::class, $e->getPrevious());
        }

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertFalse($em->getRepository(Agenda::class)->find($this->getFixture('module_agenda')->getId())->isDefault());
        self::assertTrue($this->reload()->isDefault());
    }
}
