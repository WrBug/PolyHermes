import React from 'react'
import { Select } from 'antd'
import type { Leader } from '../types'

const { Option } = Select

interface LeaderSelectProps {
  value?: number
  onChange?: (value: number | undefined) => void
  onSelectChange?: (value: number | undefined) => void  // 选择变化时的回调
  leaders: Leader[]
  placeholder?: string
  disabled?: boolean
  showSearch?: boolean
  allowClear?: boolean
  notFoundContent?: React.ReactNode
}

const LeaderSelect: React.FC<LeaderSelectProps> = ({
  value,
  onChange,
  onSelectChange,
  leaders,
  placeholder,
  disabled,
  showSearch = true,
  allowClear = false,
  notFoundContent
}) => {
  // 处理选择变化
  const handleChange = (val: number | undefined) => {
    if (onChange) {
      onChange(val)
    }
    if (onSelectChange) {
      onSelectChange(val)
    }
  }

  return (
    <Select
      value={value}
      onChange={handleChange}
      placeholder={placeholder}
      disabled={disabled}
      showSearch={showSearch}
      allowClear={allowClear}
      notFoundContent={notFoundContent}
      optionFilterProp="label"
      optionLabelProp="label"
    >
      {leaders.map(leader => {
        const label = leader.leaderName || `Leader ${leader.id}`
        return (
          <Option key={leader.id} value={leader.id} label={label}>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              <span>{label}</span>
              <span style={{ fontSize: '12px', color: '#999' }}>{leader.leaderAddress}</span>
            </div>
          </Option>
        )
      })}
    </Select>
  )
}

export default LeaderSelect
