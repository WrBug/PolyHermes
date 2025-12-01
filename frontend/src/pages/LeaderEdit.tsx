import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Form, Input, Button, Select, message, Typography, Space, Spin } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import type { Leader } from '../types'

const { Title } = Typography
const { Option } = Select

const LeaderEdit: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const leaderId = searchParams.get('id')
  
  useEffect(() => {
    if (leaderId) {
      fetchLeaderDetail(parseInt(leaderId))
    } else {
      message.error('Leader ID 无效')
      navigate('/leaders')
    }
  }, [leaderId, navigate])
  
  const fetchLeaderDetail = async (id: number) => {
    setFetching(true)
    try {
      const response = await apiService.leaders.detail({ leaderId: id })
      if (response.data.code === 0 && response.data.data) {
        const leader: Leader = response.data.data
        form.setFieldsValue({
          leaderName: leader.leaderName || '',
          category: leader.category || undefined
        })
      } else {
        message.error(response.data.msg || '获取 Leader 详情失败')
        navigate('/leaders')
      }
    } catch (error: any) {
      message.error(error.message || '获取 Leader 详情失败')
      navigate('/leaders')
    } finally {
      setFetching(false)
    }
  }
  
  const handleSubmit = async (values: any) => {
    if (!leaderId) {
      message.error('Leader ID 无效')
      return
    }
    
    setLoading(true)
    try {
      const response = await apiService.leaders.update({
        leaderId: parseInt(leaderId),
        leaderName: values.leaderName?.trim() || undefined,
        category: values.category || undefined
      })
      
      if (response.data.code === 0) {
        message.success('更新 Leader 成功')
        navigate('/leaders')
      } else {
        message.error(response.data.msg || '更新 Leader 失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新 Leader 失败')
    } finally {
      setLoading(false)
    }
  }
  
  if (fetching) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" />
      </div>
    )
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/leaders')}
          style={{ marginBottom: '16px' }}
        >
          返回
        </Button>
        <Title level={2} style={{ margin: 0 }}>编辑 Leader</Title>
      </div>
      
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label="Leader 名称"
            name="leaderName"
            tooltip="可选，用于标识 Leader，方便管理"
          >
            <Input placeholder="可选，用于标识 Leader" />
          </Form.Item>
          
          <Form.Item
            label="分类筛选"
            name="category"
            tooltip="仅跟单该分类的交易，不选择则跟单所有分类（sports 或 crypto）"
          >
            <Select placeholder="选择分类（可选）" allowClear>
              <Option value="sports">Sports</Option>
              <Option value="crypto">Crypto</Option>
            </Select>
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
              >
                保存
              </Button>
              <Button onClick={() => navigate('/leaders')}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default LeaderEdit

