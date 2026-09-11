<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Mcp\Tool\ManageAgendasTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class AgendaToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function tool(): ManageAgendasTool
    {
        return self::getContainer()->get(ManageAgendasTool::class);
    }

    public function testListWithoutUserIsRefused(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('No user bound', $data['error']);
    }

    public function testUnknownActionIsRejected(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('archive'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testCreateRequiresAName(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('create'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testListReturnsOnlyTheCurrentUserAgendas(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(2, $data['count']);
        self::assertSame(['Concerts', 'Personal'], array_column($data['agendas'], 'name'));
        self::assertArrayHasKey('timeZone', $data['agendas'][0]);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        $data = json_decode(($this->tool())('create', name: 'Sport', color: '#00bcd4', timeZone: 'Europe/Zurich'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Sport', $data['agenda']['name']);
        self::assertSame('Europe/Zurich', $data['agenda']['timeZone']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $stored = $em->getRepository(Agenda::class)->findOneBy(['name' => 'Sport']);
        self::assertNotNull($stored);
        self::assertSame('#00bcd4', $stored->getColor());

        $this->assertMercureUpdatePublished('/agendas/');
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testUpdateRenamesAndPublishes(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        /** @var Agenda $agenda */
        $agenda = $this->getFixture('concerts_agenda');

        $data = json_decode(($this->tool())('update', agendaId: (string) $agenda->getId(), name: 'Spectacles'), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Spectacles', $data['agenda']['name']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('Spectacles', $em->getRepository(Agenda::class)->find($agenda->getId())->getName());

        $this->assertMercureUpdatePublished('/agendas/');
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loadFixtures('AgendaToolsTest.yaml');
        $this->loginFixtureUser();

        /** @var Agenda $agenda */
        $agenda = $this->getFixture('concerts_agenda');

        $data = json_decode(($this->tool())('delete', agendaId: (string) $agenda->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Agenda::class)->find($agenda->getId()));

        $this->assertMercureUpdatePublished('/agendas/');
    }
}
