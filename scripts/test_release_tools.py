# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Regression checks for memory parsing and aggregation, without a connected TV."""
import unittest
from measure_memory import parse_meminfo, summarize


class MemoryChecks(unittest.TestCase):
    def test_android9_table(self):
        data = parse_meminfo(' Native Heap 4200 4000 8 0 7000 6000 1000\n Dalvik Heap 1800 1700 0 0 3000 2200 800\n TOTAL 23000 14000 2000 30 10000 8200 1800')
        self.assertEqual(data['total_pss_kb'], 23000)
        self.assertEqual(data['total_private_kb'], 16000)
        self.assertEqual(data['native_heap_private_kb'], 4008)

    def test_new_summary(self):
        self.assertEqual(parse_meminfo(' TOTAL PSS: 24000 TOTAL RSS: 60000')['total_pss_kb'], 24000)

    def test_missing_process_is_failure(self):
        with self.assertRaises(ValueError):
            parse_meminfo('No process found')

    def test_all_samples_used_and_peak_preserved(self):
        samples = [{'memory': {'total_pss_kb': n}} for n in [100, 1000, 200, 300]]
        self.assertEqual(summarize(samples)['total_pss_kb'],
                         {'min': 100, 'median': 250, 'max': 1000, 'first': 100, 'last': 300})

    def test_optional_sections_need_all_samples(self):
        values = summarize([{'memory': {'total_pss_kb': 1, 'graphics_pss_kb': 1}},
                            {'memory': {'total_pss_kb': 2}}])
        self.assertNotIn('graphics_pss_kb', values)


if __name__ == '__main__':
    unittest.main()
