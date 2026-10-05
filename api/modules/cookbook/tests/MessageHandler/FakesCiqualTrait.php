<?php

namespace Maggie\Cookbook\Tests\MessageHandler;

use Maggie\Cookbook\Service\CiqualClient;
use Symfony\Component\HttpClient\MockHttpClient;

trait FakesCiqualTrait
{
    private const COURGETTE = '20020';

    private function fakeCiqual(): void
    {
        self::getContainer()->set(CiqualClient::class, new class(new MockHttpClient()) extends CiqualClient {
            public function getFood(string $alimCode): ?array
            {
                return [
                    'alim_code' => $alimCode,
                    'alim_name_fr' => 'Courgette, crue',
                    'alim_group_code' => '02',
                    'alim_group_name_fr' => 'fruits, légumes, légumineuses et oléagineux',
                    'alim_ssgroup_code' => '0201',
                    'alim_ssgroup_name_fr' => 'légumes',
                    'nutrients' => [
                        ['const_code' => '328', 'const_name_fr' => 'Energie', 'const_unit' => 'kcal/100 g', 'value' => 19.0, 'confidence_code' => 'A', 'raw_value' => '19'],
                        ['const_code' => '25000', 'const_name_fr' => 'Protéines', 'const_unit' => 'g/100 g', 'value' => 1.2, 'confidence_code' => 'A', 'raw_value' => '1,2'],
                        ['const_code' => '31000', 'const_name_fr' => 'Glucides', 'const_unit' => 'g/100 g', 'value' => 2.3, 'confidence_code' => 'A', 'raw_value' => '2,3'],
                        ['const_code' => '40000', 'const_name_fr' => 'Lipides', 'const_unit' => 'g/100 g', 'value' => 0.4, 'confidence_code' => 'A', 'raw_value' => '0,4'],
                    ],
                ];
            }
        });
    }
}
