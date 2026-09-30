<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Mcp\Tool\ManageAgendasTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ManageAgendasClearTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ManageAgendasClearTest.yaml');
        $this->loginFixtureUser();
    }

    private function tool(): ManageAgendasTool
    {
        return self::getContainer()->get(ManageAgendasTool::class);
    }

    private function id(): string
    {
        return (string) $this->getFixture('test_agenda')->getId();
    }

    private function reload(): Agenda
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->find($this->getFixture('test_agenda')->getId());
    }

    public function testUpdateWithClearEmptiesDescriptionAndColor(): void
    {
        $data = json_decode(
            ($this->tool())('update', agendaId: $this->id(), clear: ['description', 'color', 'name']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertNull($data['agenda']['color']);
        $agenda = $this->reload();
        self::assertNull($agenda->getDescription());
        self::assertNull($agenda->getColor());
        self::assertSame('Test Agenda', $agenda->getName(), 'Required fields cannot be cleared');
        self::assertTrue($agenda->isDefault());
        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testUpdateWithoutClearLeavesNullFieldsUntouched(): void
    {
        ($this->tool())('update', agendaId: $this->id(), name: 'Renamed');

        $agenda = $this->reload();
        self::assertSame('Renamed', $agenda->getName());
        self::assertSame('Agenda notes', $agenda->getDescription());
        self::assertSame('#e91e63', $agenda->getColor());
    }

    public function testUpdateUnknownAgendaReturnsAnError(): void
    {
        $data = json_decode(($this->tool())('update', agendaId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['color']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
