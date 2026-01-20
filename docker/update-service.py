#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PolyHermes 动态更新服务
负责检查更新、下载更新包、执行更新和回滚
"""

import os
import json
import logging
import subprocess
import time
import shutil
import tarfile
import requests
from pathlib import Path
from threading import Thread
from flask import Flask, jsonify, request
from datetime import datetime

# ==================== 配置 ====================
app = Flask(__name__)

# 日志配置
LOG_FILE = Path('/var/log/polyhermes/update-service.log')
LOG_FILE.parent.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# 路径配置
APP_DIR = Path('/app')
VERSION_FILE = APP_DIR / 'version.json'
UPDATES_DIR = APP_DIR / 'updates'
BACKUPS_DIR = APP_DIR / 'backups'
BACKEND_JAR = APP_DIR / 'app.jar'
FRONTEND_DIR = Path('/usr/share/nginx/html')

# 创建必要目录
UPDATES_DIR.mkdir(parents=True, exist_ok=True)
BACKUPS_DIR.mkdir(parents=True, exist_ok=True)

# GitHub 配置
GITHUB_REPO = os.getenv('GITHUB_REPO', 'WrBug/PolyHermes')
ALLOW_PRERELEASE = os.getenv('ALLOW_PRERELEASE', 'false').lower() == 'true'
BACKEND_URL = 'http://localhost:8000'

# 更新状态
update_status = {
    'updating': False,
    'progress': 0,
    'message': '就绪',
    'error': None
}

# ==================== 工具函数 ====================

def get_current_version():
    """获取当前版本"""
    try:
        if VERSION_FILE.exists():
            with open(VERSION_FILE) as f:
                data = json.load(f)
                return data.get('version', 'unknown')
        return 'unknown'
    except Exception as e:
        logger.error(f"读取版本失败: {e}")
        return 'unknown'


def fetch_latest_release():
    """获取最新 Release"""
    try:
        if ALLOW_PRERELEASE:
            # 测试模式：获取所有 Release（包括 pre-release）
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'}, timeout=10)
            releases = response.json()
            
            if releases and len(releases) > 0:
                latest = releases[0]
                logger.info(f"检测到版本: {latest['tag_name']} (pre-release: {latest.get('prerelease', False)})")
                return {
                    'tag': latest['tag_name'],
                    'name': latest['name'],
                    'body': latest['body'],
                    'published_at': latest['published_at'],
                    'assets': latest['assets'],
                    'prerelease': latest.get('prerelease', False)
                }
        else:
            # 生产模式：只获取正式版本
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'}, timeout=10)
            
            if response.status_code == 200:
                data = response.json()
                return {
                    'tag': data['tag_name'],
                    'name': data['name'],
                    'body': data['body'],
                    'published_at': data['published_at'],
                    'assets': data['assets'],
                    'prerelease': False
                }
        
        return None
        
    except Exception as e:
        logger.error(f"获取 Release 失败: {e}")
        return None


def compare_versions(v1, v2):
    """
    比较两个版本号（语义化版本）
    返回: 1 if v1 > v2, -1 if v1 < v2, 0 if equal
    """
    def normalize(v):
        parts = v.replace('v', '').split('-')[0].split('.')
        return [int(x) for x in parts]
    
    try:
        parts1 = normalize(v1)
        parts2 = normalize(v2)
        
        for i in range(max(len(parts1), len(parts2))):
            p1 = parts1[i] if i < len(parts1) else 0
            p2 = parts2[i] if i < len(parts2) else 0
            if p1 > p2:
                return 1
            elif p1 < p2:
                return -1
        return 0
    except:
        return 0


def check_admin_permission(req):
    """检查管理员权限"""
    auth_header = req.headers.get('Authorization')
    if not auth_header:
        return False
    
    try:
        response = requests.get(
            f'{BACKEND_URL}/api/auth/verify',
            headers={'Authorization': auth_header},
            timeout=3
        )
        return response.status_code == 200
    except Exception as e:
        logger.error(f"权限验证失败: {e}")
        return False


def download_file(url, dest_path):
    """下载文件"""
    logger.info(f"开始下载: {url}")
    response = requests.get(url, stream=True, timeout=300)
    response.raise_for_status()
    
    total_size = int(response.headers.get('content-length', 0))
    downloaded = 0
    
    with open(dest_path, 'wb') as f:
        for chunk in response.iter_content(chunk_size=8192):
            if chunk:
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    progress = int((downloaded / total_size) * 30)  # 下载占30%
                    update_status['progress'] = progress
    
    logger.info(f"下载完成: {dest_path}")
    return dest_path


def backup_current_version():
    """备份当前版本"""
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    backup_dir = BACKUPS_DIR / timestamp
    backup_dir.mkdir(parents=True, exist_ok=True)
    
    logger.info(f"创建备份: {backup_dir}")
    
    # 备份后端 JAR
    if BACKEND_JAR.exists():
        shutil.copy2(BACKEND_JAR, backup_dir / 'app.jar')
    
    # 备份前端（打包）
    if FRONTEND_DIR.exists():
        frontend_backup = backup_dir / 'frontend.tar.gz'
        with tarfile.open(frontend_backup, 'w:gz') as tar:
            tar.add(FRONTEND_DIR, arcname='.')
    
    # 备份版本信息
    if VERSION_FILE.exists():
        shutil.copy2(VERSION_FILE, backup_dir / 'version.json')
    
    logger.info(f"备份完成: {backup_dir}")
    return backup_dir


def restore_backup(backup_dir):
    """恢复备份"""
    logger.info(f"开始恢复备份: {backup_dir}")
    
    # 恢复后端 JAR
    backup_jar = backup_dir / 'app.jar'
    if backup_jar.exists():
        shutil.copy2(backup_jar, BACKEND_JAR)
    
    # 恢复前端
    frontend_backup = backup_dir / 'frontend.tar.gz'
    if frontend_backup.exists():
        # 清空前端目录
        if FRONTEND_DIR.exists():
            shutil.rmtree(FRONTEND_DIR)
        FRONTEND_DIR.mkdir(parents=True, exist_ok=True)
        
        # 解压备份
        with tarfile.open(frontend_backup, 'r:gz') as tar:
            tar.extractall(FRONTEND_DIR)
    
    # 恢复版本信息
    backup_version = backup_dir / 'version.json'
    if backup_version.exists():
        shutil.copy2(backup_version, VERSION_FILE)
    
    logger.info("备份恢复完成")


def perform_update(target_version):
    """执行更新流程"""
    global update_status
    
    try:
        update_status['updating'] = True
        update_status['progress'] = 0
        update_status['message'] = '开始更新...'
        update_status['error'] = None
        
        # 1. 获取最新 Release
        update_status['message'] = '获取 Release 信息...'
        release = fetch_latest_release()
        if not release:
            raise Exception("无法获取 Release 信息")
        
        tag = release['tag']
        assets = release['assets']
        
        # 查找更新包
        update_asset = None
        for asset in assets:
            if asset['name'].endswith('-update.tar.gz'):
                update_asset = asset
                break
        
        if not update_asset:
            raise Exception(f"未找到更新包: {tag}")
        
        update_status['progress'] = 10
        
        # 2. 下载更新包
        update_status['message'] = f'下载更新包 {tag}...'
        download_url = update_asset['browser_download_url']
        download_path = UPDATES_DIR / update_asset['name']
        download_file(download_url, download_path)
        
        update_status['progress'] = 40
        
        # 3. 备份当前版本
        update_status['message'] = '备份当前版本...'
        backup_dir = backup_current_version()
        
        update_status['progress'] = 50
        
        # 4. 解压更新包
        update_status['message'] = '解压更新包...'
        extract_dir = UPDATES_DIR / 'current'
        if extract_dir.exists():
            shutil.rmtree(extract_dir)
        extract_dir.mkdir(parents=True, exist_ok=True)
        
        with tarfile.open(download_path, 'r:gz') as tar:
            tar.extractall(extract_dir)
        
        update_status['progress'] = 60
        
        # 5. 停止后端进程
        update_status['message'] = '停止后端服务...'
        logger.info("停止后端进程...")
        subprocess.run(['pkill', '-f', 'java -jar'], check=False)
        time.sleep(2)
        
        update_status['progress'] = 65
        
        # 6. 替换文件
        update_status['message'] = '更新文件...'
        
        # 替换后端 JAR
        new_jar = extract_dir / 'backend' / 'polyhermes.jar'
        if new_jar.exists():
            shutil.copy2(new_jar, BACKEND_JAR)
            logger.info("后端 JAR 已更新")
        
        # 替换前端文件
        new_frontend = extract_dir / 'frontend'
        if new_frontend.exists():
            if FRONTEND_DIR.exists():
                shutil.rmtree(FRONTEND_DIR)
            shutil.copytree(new_frontend, FRONTEND_DIR)
            logger.info("前端文件已更新")
        
        # 更新版本信息
        new_version = extract_dir / 'version.json'
        if new_version.exists():
            shutil.copy2(new_version, VERSION_FILE)
            logger.info("版本信息已更新")
        
        update_status['progress'] = 75
        
        # 7. 重启后端服务
        update_status['message'] = '重启后端服务...'
        logger.info("重启后端服务...")
        subprocess.Popen([
            'java', '-jar', str(BACKEND_JAR),
            '--spring.profiles.active=prod'
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
        update_status['progress'] = 80
        
        # 8. 重载 Nginx
        update_status['message'] = '重载 Nginx...'
        subprocess.run(['nginx', '-s', 'reload'], check=True)
        
        update_status['progress'] = 85
        
        # 9. 健康检查
        update_status['message'] = '健康检查...'
        logger.info("等待后端服务启动...")
        
        healthy = False
        for i in range(30):
            try:
                response = requests.get(f'{BACKEND_URL}/api/system/health', timeout=2)
                if response.status_code == 200:
                    healthy = True
                    break
            except:
                pass
            time.sleep(1)
        
        if not healthy:
            logger.error("健康检查失败，开始回滚...")
            update_status['message'] = '健康检查失败，回滚中...'
            restore_backup(backup_dir)
            
            # 重启后端
            subprocess.run(['pkill', '-f', 'java -jar'], check=False)
            time.sleep(1)
            subprocess.Popen([
                'java', '-jar', str(BACKEND_JAR),
                '--spring.profiles.active=prod'
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            subprocess.run(['nginx', '-s', 'reload'], check=True)
            
            raise Exception("健康检查失败，已回滚到旧版本")
        
        update_status['progress'] = 100
        update_status['message'] = f'更新成功：{tag}'
        logger.info(f"更新成功：{tag}")
        
        # 清理临时文件
        if download_path.exists():
            download_path.unlink()
        if extract_dir.exists():
            shutil.rmtree(extract_dir)
        
    except Exception as e:
        logger.error(f"更新失败: {e}")
        update_status['error'] = str(e)
        update_status['message'] = f'更新失败: {str(e)}'
    finally:
        update_status['updating'] = False


# ==================== API 路由 ====================

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({'code': 0, 'data': 'ok', 'message': 'success'})


@app.route('/version', methods=['GET'])
def version():
    """获取当前版本"""
    try:
        if VERSION_FILE.exists():
            with open(VERSION_FILE) as f:
                data = json.load(f)
                return jsonify({
                    'code': 0,
                    'data': {
                        'version': data.get('version', 'unknown'),
                        'tag': data.get('tag', 'unknown'),
                        'buildTime': data.get('buildTime', '')
                    },
                    'message': 'success'
                })
        else:
            return jsonify({
                'code': 0,
                'data': {
                    'version': 'unknown',
                    'tag': 'unknown',
                    'buildTime': ''
                },
                'message': 'success'
            })
    except Exception as e:
        logger.error(f"获取版本失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


@app.route('/check', methods=['GET'])
def check():
    """检查更新"""
    try:
        current_version = get_current_version()
        release = fetch_latest_release()
        
        if not release:
            return jsonify({
                'code': 500,
                'data': None,
                'message': '无法获取 Release 信息'
            }), 500
        
        latest_tag = release['tag']
        latest_version = latest_tag.lstrip('v')
        
        has_update = compare_versions(latest_version, current_version) > 0
        
        return jsonify({
            'code': 0,
            'data': {
                'hasUpdate': has_update,
                'currentVersion': current_version,
                'latestVersion': latest_version,
                'latestTag': latest_tag,
                'releaseNotes': release.get('body', ''),
                'publishedAt': release.get('published_at', ''),
                'prerelease': release.get('prerelease', False)
            },
            'message': 'success'
        })
        
    except Exception as e:
        logger.error(f"检查更新失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


@app.route('/update', methods=['POST'])
def update():
    """执行更新（需要管理员权限）"""
    
    # 权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    if update_status['updating']:
        return jsonify({
            'code': 409,
            'data': None,
            'message': '正在更新中，请稍后'
        }), 409
    
    # 异步执行更新
    thread = Thread(target=perform_update, args=('latest',))
    thread.start()
    
    return jsonify({
        'code': 0,
        'data': '更新已启动',
        'message': 'success'
    })


@app.route('/status', methods=['GET'])
def status():
    """获取更新状态"""
    return jsonify({
        'code': 0,
        'data': {
            'updating': update_status['updating'],
            'progress': update_status['progress'],
            'message': update_status['message'],
            'error': update_status['error']
        },
        'message': 'success'
    })


@app.route('/logs', methods=['GET'])
def logs():
    """获取更新日志（需要管理员权限）"""
    
    # 权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    try:
        if LOG_FILE.exists():
            with open(LOG_FILE) as f:
                lines = f.readlines()
                return jsonify({
                    'code': 0,
                    'data': ''.join(lines[-1000:]),  # 最后1000行
                    'message': 'success'
                })
        return jsonify({
            'code': 0,
            'data': '',
            'message': 'success'
        })
    except Exception as e:
        logger.error(f"获取日志失败: {e}")
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500


# ==================== 主程序 ====================

if __name__ == '__main__':
    logger.info("=" * 50)
    logger.info("PolyHermes 更新服务启动")
    logger.info(f"GitHub 仓库: {GITHUB_REPO}")
    logger.info(f"允许 Pre-release: {ALLOW_PRERELEASE}")
    logger.info(f"当前版本: {get_current_version()}")
    logger.info("=" * 50)
    
    # 启动 Flask 服务
    app.run(host='0.0.0.0', port=9090, debug=False)
